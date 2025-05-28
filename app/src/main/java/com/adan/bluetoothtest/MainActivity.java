package com.adan.bluetoothtest;

// 请确保您的 app/build.gradle 文件中包含以下依赖项：
// dependencies {
//     implementation "androidx.activity:activity-ktx:1.8.0" // 或更高版本
//     // 如果您只使用 Java，可以使用 activity 而不是 activity-ktx
//     // implementation "androidx.activity:activity:1.8.0" // 或更高版本
// }
// 同时，您可能也需要 androidx.fragment:fragment-ktx 或 androidx.fragment:fragment
// implementation "androidx.fragment:fragment-ktx:1.6.2" // 或更高版本

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts; // 这个导入现在应该可以正确解析
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Toast;

import com.adan.bluetoothtest.databinding.ActivityMainBinding;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {
    private ActivityMainBinding binding;
    private BluetoothAdapter bluetoothAdapter;
    private ArrayList<String> discoveredDevicesList = new ArrayList<>();
    private ArrayAdapter<String> listAdapter;

    private ConnectThread connectThread;
    private ConnectedThread connectedThread;

    private static final String TAG = "MainActivityBluetooth";
    // Standard SerialPortService ID
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // Handler for messages from threads
    private Handler handler;

    // Message types sent from the Bluetooth Handler
    public interface MessageConstants {
        public static final int MESSAGE_READ = 0;
        public static final int MESSAGE_WRITE = 1;
        public static final int MESSAGE_TOAST = 2;
        public static final int MESSAGE_STATE_CHANGE = 3;
        // ... (Add other message types as needed)
    }

    public interface ConnectionState {
        public static final int STATE_NONE = 0;       // we're doing nothing
        public static final int STATE_LISTEN = 1;     // now listening for incoming connections (not used in this client example)
        public static final int STATE_CONNECTING = 2; // now initiating an outgoing connection
        public static final int STATE_CONNECTED = 3;  // now connected to a remote device
    }


    // ActivityResultLauncher for enabling Bluetooth
    private final ActivityResultLauncher<Intent> enableBluetoothLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    Toast.makeText(this, "蓝牙已启用", Toast.LENGTH_SHORT).show();
                    listPairedDevices();
                } else {
                    Toast.makeText(this, "蓝牙未启用", Toast.LENGTH_SHORT).show();
                }
            });

    // ActivityResultLauncher for requesting permissions
    private final ActivityResultLauncher<String[]> requestPermissionsLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), permissions -> {
                boolean allGranted = true;
                for (String permission : permissions.keySet()) {
                    if (Boolean.FALSE.equals(permissions.get(permission))) {
                        allGranted = false;
                        Log.w(TAG, "权限被拒绝: " + permission);
                        break;
                    }
                }

                if (allGranted) {
                    Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show();
                    initializeBluetooth();
                } else {
                    Toast.makeText(this, "权限被拒绝。蓝牙功能将受限。", Toast.LENGTH_LONG).show();
                }
            });


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(LayoutInflater.from(this));
        setContentView(binding.getRoot());

        // Initialize the handler for communication from threads
        handler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MessageConstants.MESSAGE_STATE_CHANGE:
                        switch (msg.arg1) {
                            case ConnectionState.STATE_CONNECTED:
                                Toast.makeText(MainActivity.this, "已连接到 " + msg.obj, Toast.LENGTH_SHORT).show();
                                // TODO: 更新UI，例如启用发送按钮
                                break;
                            case ConnectionState.STATE_CONNECTING:
                                Toast.makeText(MainActivity.this, "正在连接...", Toast.LENGTH_SHORT).show();
                                break;
                            case ConnectionState.STATE_NONE:
                                Toast.makeText(MainActivity.this, "未连接", Toast.LENGTH_SHORT).show();
                                break;
                        }
                        break;
                    case MessageConstants.MESSAGE_WRITE:
                        // byte[] writeBuf = (byte[]) msg.obj;
                        // String writeMessage = new String(writeBuf);
                        // Toast.makeText(MainActivity.this, "已发送: " + writeMessage, Toast.LENGTH_SHORT).show();
                        break;
                    case MessageConstants.MESSAGE_READ:
                        byte[] readBuf = (byte[]) msg.obj;
                        String readMessage = new String(readBuf, 0, msg.arg1);
                        Toast.makeText(MainActivity.this, "已接收: " + readMessage, Toast.LENGTH_SHORT).show();
                        // TODO: 显示接收到的消息
                        break;
                    case MessageConstants.MESSAGE_TOAST:
                        Toast.makeText(MainActivity.this, msg.getData().getString("toast"),
                                Toast.LENGTH_SHORT).show();
                        break;
                }
            }
        };


        listAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, discoveredDevicesList);
        binding.lvDevices.setAdapter(listAdapter);

        binding.lvDevices.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (bluetoothAdapter.isDiscovering()) {
                    if (ActivityCompat.checkSelfPermission(MainActivity.this, getBluetoothScanPermission()) != PackageManager.PERMISSION_GRANTED) {
                        Log.w(TAG, "缺少扫描权限以在连接前取消发现。");
                        // 尝试连接，但需注意
                    }
                    bluetoothAdapter.cancelDiscovery();
                }

                String deviceInfo = (String) parent.getItemAtPosition(position);
                if (deviceInfo.startsWith("---")) return; // 忽略标题行

                String deviceAddress = deviceInfo.substring(deviceInfo.lastIndexOf("\n") + 1);
                Log.d(TAG, "尝试连接到设备: " + deviceAddress);

                BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
                connectToDevice(device);
            }
        });


        binding.btnScan.setOnClickListener(v -> {
            if (checkAndRequestPermissions()) {
                initializeBluetoothAndScan();
            }
        });

        // Register BroadcastReceiver for discovering devices
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        registerReceiver(discoveryReceiver, filter);
    }

    private boolean checkAndRequestPermissions() {
        ArrayList<String> permissionsToRequest = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        } else { // Below Android 12
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADMIN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toArray(new String[0]));
            return false;
        }
        return true;
    }

    private void initializeBluetoothAndScan() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "设备不支持蓝牙", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            if (ActivityCompat.checkSelfPermission(this, getBluetoothConnectPermission()) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "缺少 BLUETOOTH_CONNECT 权限以执行 ACTION_REQUEST_ENABLE。在某些系统上这可能静默失败或崩溃。");
                // 理想情况下，在此调用之前确保已授予连接权限。
                // 目前，继续执行并让系统处理（如果可以）。
            }
            enableBluetoothLauncher.launch(enableBtIntent);
        } else {
            listPairedDevices();
            startDiscovery();
        }
    }

    private void initializeBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "设备不支持蓝牙", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            if (ActivityCompat.checkSelfPermission(this, getBluetoothConnectPermission()) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "缺少 BLUETOOTH_CONNECT 权限以执行 ACTION_REQUEST_ENABLE。");
            }
            enableBluetoothLauncher.launch(enableBtIntent);
        } else {
            listPairedDevices();
        }
    }

    private void listPairedDevices() {
        if (ActivityCompat.checkSelfPermission(this, getBluetoothConnectPermission()) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "缺少 BLUETOOTH_CONNECT 权限以列出已配对设备。", Toast.LENGTH_SHORT).show();
            return;
        }
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        discoveredDevicesList.clear();
        listAdapter.notifyDataSetChanged();

        if (pairedDevices != null && pairedDevices.size() > 0) {
            discoveredDevicesList.add("--- 已配对设备 ---");
            for (BluetoothDevice device : pairedDevices) {
                String deviceName = device.getName();
                String deviceHardwareAddress = device.getAddress();
                String deviceInfo = (deviceName == null || deviceName.isEmpty() ? "未知设备" : deviceName) + "\n" + deviceHardwareAddress;
                if (!discoveredDevicesList.contains(deviceInfo)) {
                    discoveredDevicesList.add(deviceInfo);
                }
            }
            listAdapter.notifyDataSetChanged();
        }
    }

    private void startDiscovery() {
        if (ActivityCompat.checkSelfPermission(this, getBluetoothScanPermission()) != PackageManager.PERMISSION_GRANTED ||
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
        ) {
            Toast.makeText(this, "缺少扫描权限。", Toast.LENGTH_SHORT).show();
            return;
        }

        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
        boolean discoveryStarted = bluetoothAdapter.startDiscovery();
        if(discoveryStarted) {
            Toast.makeText(this, "正在扫描新设备...", Toast.LENGTH_SHORT).show();
            if (!discoveredDevicesList.contains("--- 新设备 ---")) {
                discoveredDevicesList.add("--- 新设备 ---");
                listAdapter.notifyDataSetChanged();
            }
        } else {
            Toast.makeText(this, "启动发现失败。", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "BluetoothAdapter.startDiscovery() 返回 false。请检查权限和适配器状态。");
        }
    }

    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    String deviceName = null;
                    String deviceHardwareAddress = device.getAddress(); // 地址通常可用

                    if (ActivityCompat.checkSelfPermission(MainActivity.this, getBluetoothConnectPermission()) == PackageManager.PERMISSION_GRANTED) {
                        deviceName = device.getName(); // 对于未配对设备，在 API 31+ 上获取名称需要 BLUETOOTH_CONNECT 权限
                    } else {
                        Log.w(TAG, "缺少 BLUETOOTH_CONNECT 权限以获取已发现设备的名称。");
                    }

                    String deviceInfo = (deviceName == null || deviceName.isEmpty() ? "未知设备" : deviceName) + "\n" + deviceHardwareAddress;

                    boolean newDevicesHeaderPresent = discoveredDevicesList.contains("--- 新设备 ---");
                    if (!newDevicesHeaderPresent) {
                        // 如果之前未列出已配对设备或列表已清除，则可能发生此情况
                        // 如果这是第一个“新设备”部分，请确保添加标题
                        int pairedHeaderIndex = discoveredDevicesList.indexOf("--- 已配对设备 ---");
                        if (pairedHeaderIndex != -1 && pairedHeaderIndex == discoveredDevicesList.size() -1) {
                            // 仅存在已配对设备标题，在其后添加新设备标题
                            discoveredDevicesList.add("--- 新设备 ---");
                        } else if (pairedHeaderIndex == -1) {
                            // 根本没有标题，添加新设备标题
                            discoveredDevicesList.add("--- 新设备 ---");
                        }
                        // 如果存在已配对设备并且缺少新设备标题，则插入操作会很复杂。
                        // 为简单起见，如果不存在就添加它。
                        if(!discoveredDevicesList.contains("--- 新设备 ---")) discoveredDevicesList.add("--- 新设备 ---");

                    }

                    if (!discoveredDevicesList.contains(deviceInfo)) {
                        discoveredDevicesList.add(deviceInfo);
                        listAdapter.notifyDataSetChanged();
                        Log.d(TAG, "发现设备: " + deviceInfo);
                    }
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                Log.d(TAG, "发现已开始。");
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                Log.d(TAG, "发现已完成。");
                Toast.makeText(context, "扫描完成。", Toast.LENGTH_SHORT).show();
                // 检查“--- 新设备 ---”标题是否存在，以及其后是否添加了任何实际设备。
                int newDevicesHeaderIdx = discoveredDevicesList.indexOf("--- 新设备 ---");
                if (newDevicesHeaderIdx != -1 && newDevicesHeaderIdx == discoveredDevicesList.size() - 1) {
                    discoveredDevicesList.add("未发现新设备");
                    listAdapter.notifyDataSetChanged();
                } else if (newDevicesHeaderIdx == -1 && discoveredDevicesList.stream().noneMatch(s -> !s.startsWith("---"))) {
                    // 没有标题也没有实际设备（如果已清除，则只有可能的“--- 已配对设备 ---”）
                    // 此逻辑可以简化：如果扫描后列表仅包含标题或为空，则显示“无设备”。
                }
            }
        }
    };

    private String getBluetoothScanPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return Manifest.permission.BLUETOOTH_SCAN;
        } else {
            return Manifest.permission.BLUETOOTH_ADMIN;
        }
    }

    private String getBluetoothConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return Manifest.permission.BLUETOOTH_CONNECT;
        } else {
            // 对于连接，在较旧版本上 BLUETOOTH 通常足够
            // BLUETOOTH_ADMIN 更常用于发现和适配器操作
            return Manifest.permission.BLUETOOTH;
        }
    }

    public synchronized void connectToDevice(BluetoothDevice device) {
        Log.d(TAG, "连接到: " + device);

        // Cancel any thread attempting to make a connection
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }

        // Cancel any thread currently running a connection
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        // Start the thread to connect with the given device
        connectThread = new ConnectThread(device);
        connectThread.start();
        updateConnectionState(ConnectionState.STATE_CONNECTING, device.getName());
    }

    private synchronized void manageConnectedSocket(BluetoothSocket socket, BluetoothDevice device) {
        Log.d(TAG, "manageConnectedSocket 已为 " + device.getName() + " 启动");
        // Cancel the thread that completed the connection
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }

        // Cancel any thread currently running a connection
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        // Start the thread to manage the connection and perform transmissions
        connectedThread = new ConnectedThread(socket);
        connectedThread.start();

        updateConnectionState(ConnectionState.STATE_CONNECTED, device.getName());
    }

    private synchronized void updateConnectionState(int state, String deviceName) {
        handler.obtainMessage(MessageConstants.MESSAGE_STATE_CHANGE, state, -1, deviceName).sendToTarget();
    }

    private void connectionFailed() {
        Log.e(TAG, "连接失败");
        Message msg = handler.obtainMessage(MessageConstants.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString("toast", "无法连接设备");
        msg.setData(bundle);
        handler.sendMessage(msg);
        updateConnectionState(ConnectionState.STATE_NONE, null);
        // TODO: 考虑重新启动侦听模式或其他恢复操作
    }

    private void connectionLost() {
        Log.e(TAG, "连接丢失");
        Message msg = handler.obtainMessage(MessageConstants.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString("toast", "设备连接已丢失");
        msg.setData(bundle);
        handler.sendMessage(msg);
        updateConnectionState(ConnectionState.STATE_NONE, null);
        // TODO: 考虑重新启动侦听模式或其他恢复操作
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(discoveryReceiver);
        if (bluetoothAdapter != null) {
            if (ActivityCompat.checkSelfPermission(this, getBluetoothScanPermission()) == PackageManager.PERMISSION_GRANTED) {
                if (bluetoothAdapter.isDiscovering()) {
                    bluetoothAdapter.cancelDiscovery();
                }
            } else {
                Log.w(TAG, "onDestroy 中缺少扫描权限以取消发现。");
            }
        }
        if (connectThread != null) {
            connectThread.cancel();
        }
        if (connectedThread != null) {
            connectedThread.cancel();
        }
    }

    /**
     * This thread runs while attempting to make an outgoing connection
     * with a device. It runs straight through; the connection either
     * succeeds or fails.
     */
    private class ConnectThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;

        public ConnectThread(BluetoothDevice device) {
            mmDevice = device;
            BluetoothSocket tmp = null;
            // Get a BluetoothSocket for a connection with the given BluetoothDevice
            try {
                if (ActivityCompat.checkSelfPermission(MainActivity.this, getBluetoothConnectPermission()) != PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "缺少 BLUETOOTH_CONNECT 权限以创建 RFCOMM 套接字。");
                    // 妥善处理此错误，例如通知用户或正常失败。
                    // 在此示例中，我们将让它继续执行，并可能在 createRfcommSocketToServiceRecord 处失败。
                    // 一个健壮的应用程序会在尝试获取设备对象之前确保权限。
                }
                tmp = device.createRfcommSocketToServiceRecord(MY_UUID);
            } catch (IOException e) {
                Log.e(TAG, "套接字的 create() 方法失败", e);
            }
            mmSocket = tmp;
        }

        public void run() {
            Log.i(TAG, "BEGIN mConnectThread SocketType:" + mmDevice);
            setName("ConnectThread" + mmDevice);

            // Always cancel discovery because it will slow down a connection
            if (bluetoothAdapter.isDiscovering()) {
                if (ActivityCompat.checkSelfPermission(MainActivity.this, getBluetoothScanPermission()) != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "ConnectThread 中缺少扫描权限以取消发现。");
                } else {
                    bluetoothAdapter.cancelDiscovery();
                }
            }


            // Make a connection to the BluetoothSocket
            try {
                // This is a blocking call and will only return on a
                // successful connection or an exception
                if (mmSocket == null) {
                    Log.e(TAG, "mmSocket 为空，无法连接。");
                    connectionFailed();
                    return;
                }
                mmSocket.connect();
            } catch (IOException e) {
                // Close the socket
                Log.e(TAG, "无法连接；关闭服务器套接字", e);
                try {
                    mmSocket.close();
                } catch (IOException e2) {
                    Log.e(TAG, "连接失败期间无法 close() 套接字", e2);
                }
                connectionFailed();
                return;
            }

            // Reset the ConnectThread because we're done
            synchronized (MainActivity.this) {
                connectThread = null;
            }

            // Start the connected thread
            manageConnectedSocket(mmSocket, mmDevice);
        }

        // Closes the client socket and causes the thread to finish.
        public void cancel() {
            try {
                if (mmSocket != null) {
                    mmSocket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "connect 套接字的 close() 失败", e);
            }
        }
    }

    /**
     * This thread runs during a connection with a remote device.
     * It handles all incoming and outgoing transmissions.
     */
    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;
        private byte[] mmBuffer; // mmBuffer store for the stream

        public ConnectedThread(BluetoothSocket socket) {
            Log.d(TAG, "创建 ConnectedThread");
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the BluetoothSocket input and output streams
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "临时套接字未创建", e);
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {
            Log.i(TAG, "BEGIN mConnectedThread");
            mmBuffer = new byte[1024];
            int numBytes; // bytes returned from read()

            // Keep listening to the InputStream until an exception occurs.
            while (true) {
                try {
                    // Read from the InputStream.
                    numBytes = mmInStream.read(mmBuffer);
                    // Send the obtained bytes to the UI activity.
                    Message readMsg = handler.obtainMessage(
                            MessageConstants.MESSAGE_READ, numBytes, -1,
                            mmBuffer);
                    readMsg.sendToTarget();
                } catch (IOException e) {
                    Log.d(TAG, "输入流已断开", e);
                    connectionLost();
                    break; // 发生错误时退出循环
                }
            }
        }

        // Call this from the main activity to send data to the remote device.
        public void write(byte[] bytes) {
            try {
                mmOutStream.write(bytes);
                // Share the sent message with the UI activity.
                // Message writtenMsg = handler.obtainMessage(
                //        MessageConstants.MESSAGE_WRITE, -1, -1, bytes);
                // writtenMsg.sendToTarget(); // 您可能不希望每次写入都显示 Toast
                Log.d(TAG, "数据已发送: " + new String(bytes));
            } catch (IOException e) {
                Log.e(TAG, "发送数据时发生错误", e);
                // Send a failure message back to the activity.
                // Message writeErrorMsg = handler.obtainMessage(MessageConstants.MESSAGE_TOAST);
                // Bundle bundle = new Bundle();
                // bundle.putString("toast", "无法将数据发送到其他设备");
                // writeErrorMsg.setData(bundle);
                // handler.sendMessage(writeErrorMsg);
                connectionLost(); // 如果写入失败，则假定连接已丢失
            }
        }

        // Call this method from the main activity to shut down the connection.
        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "无法关闭连接套接字", e);
            }
        }
    }
}
