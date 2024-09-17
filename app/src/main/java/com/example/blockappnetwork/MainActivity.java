package com.example.blockappnetwork;

import android.Manifest;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS = 1;

    private EditText editText;
    private TextView resultTextView;
    private Process tcpdumpProcess;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        editText = findViewById(R.id.myEditText);
        resultTextView = findViewById(R.id.resultTextView);

        Button blockButton = findViewById(R.id.button);
        Button unblockButton = findViewById(R.id.button2);
        Button pullButton = findViewById(R.id.button3);

        blockButton.setOnClickListener(v -> {
            blockAppNetwork();
            startTcpdump();
        });

        unblockButton.setOnClickListener(v -> {
            unblockAppNetwork();
            stopTcpdump();
        });

        pullButton.setOnClickListener(v -> {
            startHttpServer();
            Toast.makeText(this, "HTTP server started. Access files ", Toast.LENGTH_LONG).show();
        });

        editText.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String packageName = getPackageNameFromAppName(s.toString());
                if (packageName != null) {
                    resultTextView.setText(packageName);
                } else {
                    resultTextView.setText("No matching package");
                }
            }
        });

        // Request permissions
        requestPermissions();
    }

    private void requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.INTERNET},
                    REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permissions granted
            } else {
                Toast.makeText(this, "Permissions denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void blockAppNetwork() {
        String packageName = resultTextView.getText().toString();
        if (!packageName.equals("No matching package")) {
            String userId = getUserIdFromPackageName(packageName);
            if (userId != null) {
                executeCommands(new String[]{
                        "iptables -A OUTPUT -m owner --uid-owner " + userId + " -j ACCEPT",
                        "iptables -A INPUT -m owner --uid-owner " + userId + " -j ACCEPT",
                        "iptables -A INPUT -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT",
                        "iptables -A OUTPUT -m owner ! --uid-owner " + userId + " -j REJECT",
                        "iptables -A INPUT -m owner ! --uid-owner " + userId + " -j REJECT"
                });
            } else {
                Toast.makeText(this, "Failed to get user ID for package: " + packageName, Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "No valid package name", Toast.LENGTH_SHORT).show();
        }
    }

    private void unblockAppNetwork() {
        executeCommands(new String[]{
                "iptables -F",
                "iptables -X"
        });
    }

    private void startTcpdump() {
        try {
            String packageName = resultTextView.getText().toString();
            String captureFileName = packageName + "_capture.pcap";
            String captureFilePath = "/sdcard/capture/" + captureFileName;
            tcpdumpProcess = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(tcpdumpProcess.getOutputStream());
            os.writeBytes("mkdir -p /sdcard/capture\n");
            os.writeBytes("/data/local/tmp/tcpdump -i any -w " + captureFilePath + "\n");
            os.flush();
            os.writeBytes("exit\n");
            os.flush();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "Failed to start tcpdump", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopTcpdump() {
        try {
            if (tcpdumpProcess != null) {
                executeCommands(new String[]{"su", "-c", "pkill tcpdump" });
                tcpdumpProcess = null;
                Toast.makeText(this, "Tcpdump stopped and file saved", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Failed to stop tcpdump", Toast.LENGTH_SHORT).show();
        }
    }

    private void startHttpServer() {
        new Thread(() -> {
            try {
                String packageName = resultTextView.getText().toString();
                String captureFileName = packageName + "_capture.pcap";
                String captureFilePath = "/sdcard/capture/" + captureFileName;
                File file = new File(captureFilePath);
                if (file.exists()) {
                    uploadFile(file);
                } else {
                    runOnUiThread(() -> Toast.makeText(this, "Capture file not found", Toast.LENGTH_SHORT).show());
                }
            } catch (IOException e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(this, "Failed to send file to server", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private String getPackageNameFromAppName(String appName) {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        for (ApplicationInfo packageInfo : packages) {
            String label = pm.getApplicationLabel(packageInfo).toString();
            if (label.equalsIgnoreCase(appName)) {
                return packageInfo.packageName;
            }
        }
        return null;
    }

    private String getUserIdFromPackageName(String packageName) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", "dumpsys package " + packageName + " | grep userId"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            reader.close();
            String result = output.toString();
            String[] splitResult = result.split("userId=");
            if (splitResult.length > 1) {
                return splitResult[1].trim().split(" ")[0];
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private void executeCommands(String[] commands) {
        try {
            Process su = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(su.getOutputStream());
            for (String cmd : commands) {
                os.writeBytes(cmd + "\n");
            }
            os.writeBytes("exit\n");
            os.flush();
            os.close();
            su.waitFor();
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            Toast.makeText(this, "Failed to execute commands", Toast.LENGTH_SHORT).show();
        }
    }

    private void uploadFile(File file) throws IOException {
        String serverUrl = "http://192.168.31.59:5000/upload";
        HttpURLConnection connection = null;
        DataOutputStream outputStream = null;
        FileInputStream fileInputStream = new FileInputStream(file);

        try {
            URL url = new URL(serverUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setDoInput(true);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Connection", "Keep-Alive");
            connection.setRequestProperty("Content-Type", "multipart/form-data;boundary=*****");

            outputStream = new DataOutputStream(connection.getOutputStream());
            outputStream.writeBytes("--*****\r\n");
            outputStream.writeBytes("Content-Disposition: form-data; name=\"file\";filename=\"" + file.getName() + "\"\r\n");
            outputStream.writeBytes("\r\n");

            int bytesRead;
            byte[] buffer = new byte[1024];
            while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }

            outputStream.writeBytes("\r\n");
            outputStream.writeBytes("--*****--\r\n");

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                runOnUiThread(() -> Toast.makeText(this, "File uploaded successfully", Toast.LENGTH_SHORT).show());
            } else {
                runOnUiThread(() -> Toast.makeText(this, "Failed to upload file. Response code: " + responseCode, Toast.LENGTH_SHORT).show());
            }
        } catch (IOException e) {
            e.printStackTrace();
            runOnUiThread(() -> Toast.makeText(this, "Failed to send file to server: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        } finally {
            if (fileInputStream != null) {
                fileInputStream.close();
            }
            if (outputStream != null) {
                outputStream.flush();
                outputStream.close();
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}