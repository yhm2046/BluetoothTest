package com.adan.bluetoothtest;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.annotation.SuppressLint;
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
import android.widget.ArrayAdapter;
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
    private long scanStartTime;  // 新增变量：记录扫描开始时间
    private static final String TAG = "MainActivityBluetooth";
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private Handler handler;

    public interface MessageConstants {
        int MESSAGE_READ = 0;
        int MESSAGE_WRITE = 1;
        int MESSAGE_TOAST = 2;
        int MESSAGE_STATE_CHANGE = 3;
    }

    public interface ConnectionState {
        int STATE_NONE = 0;
        int STATE_LISTEN = 1;
        int STATE_CONNECTING = 2;
        int STATE_CONNECTED = 3;
    }

    private final ActivityResultLauncher<Intent> enableBluetoothLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    Toast.makeText(this, "蓝牙已启用", Toast.LENGTH_SHORT).show();
                    listPairedDevices();
                } else {
                    Toast.makeText(this, "蓝牙未启用", Toast.LENGTH_SHORT).show();
                }
            });

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
                    initializeBluetoothAndScan(); // 权限授予后，初始化并扫描
                } else {
                    Toast.makeText(this, "权限被拒绝。蓝牙功能将受限。", Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(LayoutInflater.from(this));
        setContentView(binding.getRoot());

        handler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MessageConstants.MESSAGE_STATE_CHANGE:
                        String deviceNameForToast = (String) msg.obj;
                        switch (msg.arg1) {
                            case ConnectionState.STATE_CONNECTED:
                                Toast.makeText(MainActivity.this, "已连接到 " + deviceNameForToast, Toast.LENGTH_SHORT).show();
                                break;
                            case ConnectionState.STATE_CONNECTING:
                                Toast.makeText(MainActivity.this, "正在连接 " + deviceNameForToast + "...", Toast.LENGTH_SHORT).show();
                                break;
                            case ConnectionState.STATE_NONE:
                                Toast.makeText(MainActivity.this, "未连接", Toast.LENGTH_SHORT).show();
                                break;
                        }
                        break;
                    case MessageConstants.MESSAGE_READ:
                        byte[] readBuf = (byte[]) msg.obj;
                        String readMessage = new String(readBuf, 0, msg.arg1);
                        Toast.makeText(MainActivity.this, "已接收: " + readMessage, Toast.LENGTH_SHORT).show();
                        break;
                    case MessageConstants.MESSAGE_TOAST:
                        Toast.makeText(MainActivity.this, msg.getData().getString("toast"), Toast.LENGTH_SHORT).show();
                        break;
                }
            }
        };

        listAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, discoveredDevicesList);
        binding.lvDevices.setAdapter(listAdapter);

        binding.lvDevices.setOnItemClickListener((parent, view, position, id) -> {
            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
                Toast.makeText(MainActivity.this, "请先启用蓝牙", Toast.LENGTH_SHORT).show();
                return;
            }

            String deviceInfo = (String) parent.getItemAtPosition(position);
            if (deviceInfo.startsWith("---")) return;  // 跳过无效项 (Skip invalid items)

            String deviceAddress = deviceInfo.substring(deviceInfo.lastIndexOf("\n") + 1);
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            String dialogDeviceName = deviceInfo.substring(0, deviceInfo.indexOf("\n")); // 从列表项获取名称用于弹窗 (Get name from list item for dialog)

            // 检查扫描权限以在连接前停止扫描 (Check scan permission to stop discovery before connecting)
            if (ActivityCompat.checkSelfPermission(this, getBluetoothScanPermission()) == PackageManager.PERMISSION_GRANTED) {
                if (bluetoothAdapter.isDiscovering()) {
                    bluetoothAdapter.cancelDiscovery();
                    Log.d(TAG, "Discovery cancelled before attempting connection.");
                }
            } else {
                Log.w(TAG, "Scan permission missing, cannot cancel discovery. Proceeding with connection attempt.");
            }


            // 判断设备是否已连接 (Determine if the device is already connected)
            boolean isCurrentlyConnected = (connectedThread != null &&
                    connectedThread.mmSocket != null &&
                    connectedThread.mmSocket.isConnected() &&
                    connectedThread.mmSocket.getRemoteDevice().getAddress().equals(deviceAddress));

            if (isCurrentlyConnected) {
                // 已连接，显示断开连接弹窗 (Connected, show disconnect dialog)
                new AlertDialog.Builder(this)
                        .setTitle("断开连接")
                        .setMessage("是否断开与 " + dialogDeviceName + " 的连接？")
                        .setPositiveButton("断开", (dialog, which) -> {
                            if (connectedThread != null) {
                                connectedThread.cancel();
                                connectedThread = null;
                            }
                            Toast.makeText(this, "已断开连接", Toast.LENGTH_SHORT).show();
                            updateConnectionState(ConnectionState.STATE_NONE, null);
                        })
                        .setNegativeButton("取消", null)
                        .show();
            } else {
                // 未连接，显示连接弹窗 (Not connected, show connect dialog)
                new AlertDialog.Builder(this)
                        .setTitle("连接设备")
                        .setMessage("是否连接到 " + dialogDeviceName + "？")
                        .setPositiveButton("连接", (dialog, which) -> connectToDevice(device))  // 调用连接方法 (Call connect method)
                        .setNegativeButton("取消", null)
                        .show();
            }
        });

        binding.btnScan.setOnClickListener(v -> {
            Log.d(TAG, "扫描按钮被点击");
            if (checkAndRequestPermissions()) {
                Log.d(TAG, "权限已授予，正在初始化蓝牙并扫描...");
                initializeBluetoothAndScan();
            } else {
                Log.d(TAG, "正在请求权限...");
            }
        });

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        registerReceiver(discoveryReceiver, filter);

        // 首次启动时检查权限并初始化蓝牙 (Check permissions and initialize Bluetooth on first start)
        if (checkAndRequestPermissions()) {
            initializeBluetooth(); // 仅列出已配对的，不主动扫描 (Only list paired, don't scan actively)
        }
    }

    private boolean checkAndRequestPermissions() {
        ArrayList<String> permissionsToRequest = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADMIN);
            }
        }
        // 定位权限对于发现设备是必要的 (Location permission is necessary for device discovery)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (!permissionsToRequest.isEmpty()) {
            Log.d(TAG, "请求权限: " + permissionsToRequest);
            requestPermissionsLauncher.launch(permissionsToRequest.toArray(new String[0]));
            return false;
        }
        Log.d(TAG, "所有必要的蓝牙权限已授予。");
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
                Log.w(TAG, "缺少 BLUETOOTH_CONNECT 权限以执行 ACTION_REQUEST_ENABLE。");
                // 即使缺少权限也尝试启动，系统可能会处理或失败 (Try to launch even if permission is missing, system might handle or fail)
            }
            enableBluetoothLauncher.launch(enableBtIntent);
        } else {
            Log.d(TAG, "蓝牙已启用。正在列出已配对设备并开始扫描...");
            listPairedDevices(); // 确保已配对设备首先被列出或刷新 (Ensure paired devices are listed or refreshed first)
            startDiscovery();    // 然后开始扫描新设备 (Then start scanning for new devices)
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


    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is checked before calling this method
    private void listPairedDevices() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Log.w(TAG, "listPairedDevices: Bluetooth not enabled or adapter null");
            return;
        }

        if (!checkPermission(getBluetoothConnectPermission())) {
            Toast.makeText(this, "缺少蓝牙连接权限以列出已配对设备。", Toast.LENGTH_SHORT).show();
            Log.w(TAG, "listPairedDevices: BLUETOOTH_CONNECT permission missing.");
            return;
        }

        discoveredDevicesList.clear(); // 清空整个列表准备重新填充 (Clear entire list to repopulate)
        listAdapter.notifyDataSetChanged(); // 更新UI以反映清空 (Update UI to reflect clearing)

        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        discoveredDevicesList.add("--- 已配对设备 ---"); // 添加头部 (Add header)

        if (pairedDevices != null && !pairedDevices.isEmpty()) {
            for (BluetoothDevice device : pairedDevices) {
                String deviceName = device.getName();
                String deviceHardwareAddress = device.getAddress();
                String deviceInfo = (deviceName == null || deviceName.isEmpty() ? "未知设备 (已配对)" : deviceName) + "\n" + deviceHardwareAddress;
                discoveredDevicesList.add(deviceInfo);
            }
        } else {
            discoveredDevicesList.add("无已配对设备");
            Log.d(TAG, "没有已配对的设备");
        }
        listAdapter.notifyDataSetChanged();
    }

    @SuppressLint("MissingPermission") // BLUETOOTH_SCAN and ACCESS_FINE_LOCATION checked before calling
    private void startDiscovery() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Log.w(TAG, "startDiscovery: Bluetooth not enabled or adapter null");
            return;
        }

        if (!checkPermission(getBluetoothScanPermission()) || !checkPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            Toast.makeText(this, "扫描权限缺失。", Toast.LENGTH_SHORT).show();
            Log.w(TAG, "startDiscovery: Scanning permissions missing.");
            binding.btnScan.setEnabled(true); // 确保按钮可用如果无法启动扫描 (Ensure button is enabled if scan cannot start)
            return;
        }

        scanStartTime = System.currentTimeMillis(); // 记录扫描开始时间戳 (Record scan start timestamp)
        binding.btnScan.setEnabled(false); // 禁用扫描按钮 (Disable scan button)
        Log.d(TAG, "扫描按钮已禁用 (Scan button disabled)");


        // 清理之前的“新设备”部分，保留“已配对设备”部分
        // (Clean up previous "New Devices" section, keep "Paired Devices" section)
        int newDevicesHeaderIndex = discoveredDevicesList.indexOf("--- 新设备 ---");
        if (newDevicesHeaderIndex != -1) {
            // 从 "--- 新设备 ---" 头部开始移除之后的所有项
            // (Remove all items from the "--- 新设备 ---" header onwards)
            while (discoveredDevicesList.size() > newDevicesHeaderIndex) {
                discoveredDevicesList.remove(newDevicesHeaderIndex);
            }
        }
        // 如果列表中没有 "--- 新设备 ---" 头部（可能因为之前没有扫描到新设备），现在添加它
        // (If "--- 新设备 ---" header isn't in the list (perhaps no new devices found previously), add it now)
        if(discoveredDevicesList.indexOf("--- 新设备 ---") == -1) { // 确保在取消/开始新扫描时总是有一个新设备区域
            discoveredDevicesList.add("--- 新设备 ---");
        }
        listAdapter.notifyDataSetChanged();


        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
            Log.d(TAG, "已取消正在进行的扫描 (Cancelled ongoing discovery)");
        }

        boolean discoveryStarted = bluetoothAdapter.startDiscovery();
        if (discoveryStarted) {
            Log.d(TAG, "开始扫描新设备... (Started discovery for new devices...)");
            // "--- 新设备 ---" 头部应该已经存在了 (The "--- 新设备 ---" header should already exist)
        } else {
            Log.e(TAG, "BluetoothAdapter.startDiscovery() 返回 false。请检查权限和适配器状态。(BluetoothAdapter.startDiscovery() returned false. Check permissions and adapter state.)");
            Toast.makeText(this, "启动发现失败。", Toast.LENGTH_SHORT).show();
            binding.btnScan.setEnabled(true); // 如果启动失败，重新启用按钮 (If start fails, re-enable button)
        }
    }

    private boolean checkPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }


    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @SuppressLint("MissingPermission") // Permissions are checked before scan or by getBluetoothConnectPermission()
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    String deviceName = "未知设备 (扫描)";
                    String deviceHardwareAddress = device.getAddress();

                    if (checkPermission(getBluetoothConnectPermission())) {
                        String fetchedName = device.getName();
                        if (fetchedName != null && !fetchedName.isEmpty()) {
                            deviceName = fetchedName;
                        } else {
                            Log.w(TAG, "ACTION_FOUND: device.getName() returned null/empty with BLUETOOTH_CONNECT. Address: " + deviceHardwareAddress);
                        }
                    } else {
                        Log.w(TAG, "ACTION_FOUND: BLUETOOTH_CONNECT permission missing for getName(). Address: " + deviceHardwareAddress);
                    }

                    String deviceInfo = deviceName + "\n" + deviceHardwareAddress;

                    boolean alreadyExists = false;
                    int existingIndex = -1;
                    for (int i = 0; i < discoveredDevicesList.size(); i++) {
                        if (discoveredDevicesList.get(i).endsWith(deviceHardwareAddress)) {
                            alreadyExists = true;
                            existingIndex = i;
                            break;
                        }
                    }

                    if (alreadyExists) {
                        if (discoveredDevicesList.get(existingIndex).startsWith("未知设备") && !deviceName.startsWith("未知设备")) {
                            discoveredDevicesList.set(existingIndex, deviceInfo);
                            listAdapter.notifyDataSetChanged();
                            Log.d(TAG, "更新设备信息: " + deviceInfo);
                        }
                    } else {
                        int newDevicesHeaderIndex = discoveredDevicesList.indexOf("--- 新设备 ---");
                        if (newDevicesHeaderIndex == -1) {
                            // 理论上 startDiscovery() 应该已经添加了这个头部
                            // (Theoretically, startDiscovery() should have added this header)
                            discoveredDevicesList.add("--- 新设备 ---");
                            newDevicesHeaderIndex = discoveredDevicesList.size() -1; // 指向新添加的头部 (Points to the newly added header)
                        }
                        // 将新设备添加到 "--- 新设备 ---" 标题之后，或者如果标题是最后一个，则直接添加
                        // (Add new device after "--- 新设备 ---" header, or directly if header is last)
                        discoveredDevicesList.add(deviceInfo);
                        listAdapter.notifyDataSetChanged();
                        Log.d(TAG, "发现新设备: " + deviceInfo);
                    }
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                Log.d(TAG, "Discovery started.");
                binding.btnScan.setEnabled(false); // 扫描开始时禁用按钮 (Disable button when scan starts)
                Log.d(TAG, "扫描按钮在 ACTION_DISCOVERY_STARTED 中被禁用 (Scan button disabled in ACTION_DISCOVERY_STARTED)");
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                Log.d(TAG, "Discovery finished.");
                binding.btnScan.setEnabled(true);  // 扫描结束时启用按钮 (Enable button when scan finishes)
                Log.d(TAG, "扫描按钮在 ACTION_DISCOVERY_FINISHED 中被启用 (Scan button enabled in ACTION_DISCOVERY_FINISHED)");

                int deviceCount = countDevices();
                long scanDuration = System.currentTimeMillis() - scanStartTime;
                int minutes = (int) (scanDuration / 60000);
                int seconds = (int) ((scanDuration % 60000) / 1000);
                showRescanDialog(deviceCount, minutes, seconds);
            }
        }
    };

    private int countDevices() {
        int count = 0;
        for (String item : discoveredDevicesList) {
            if (!item.startsWith("---")) {
                count++;
            }
        }
        return count;
    }

    private void showRescanDialog(int deviceCount, int minutes, int seconds) {
        @SuppressLint("DefaultLocale") String message = String.format("扫描完成，共搜索到%d个设备，耗时%d分%d秒，是否重新扫描？",
                deviceCount, minutes, seconds);
        new AlertDialog.Builder(this)
                .setTitle("扫描完成")
                .setMessage(message)
                .setPositiveButton("重新扫描", (dialog, which) -> {
                    if (checkAndRequestPermissions()) { // 再次检查权限以防万一 (Re-check permissions just in case)
                        startDiscovery();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String getBluetoothScanPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? Manifest.permission.BLUETOOTH_SCAN : Manifest.permission.BLUETOOTH_ADMIN;
    }

    private String getBluetoothConnectPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? Manifest.permission.BLUETOOTH_CONNECT : Manifest.permission.BLUETOOTH;
    }

    @SuppressLint("MissingPermission") // Permissions are checked at the start of this method
    public synchronized void connectToDevice(BluetoothDevice device) {
        if (!checkPermission(getBluetoothConnectPermission())) {
            Toast.makeText(this,"缺少蓝牙连接权限，无法连接。", Toast.LENGTH_SHORT).show();
            Log.e(TAG,"connectToDevice: BLUETOOTH_CONNECT permission missing.");
            return;
        }

        String deviceNameForToast = device.getAddress(); // 默认使用地址 (Default to address)
        String fetchedName = device.getName(); // 尝试获取名称 (Try to get name)
        if (fetchedName != null && !fetchedName.isEmpty()) {
            deviceNameForToast = fetchedName;
        }
        Log.d(TAG, "connect to: " + deviceNameForToast + " (" + device.getAddress() + ")");


        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }
        connectThread = new ConnectThread(device);
        connectThread.start();
        updateConnectionState(ConnectionState.STATE_CONNECTING, deviceNameForToast);
    }

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is checked before calling getName or used with fallback
    private synchronized void manageConnectedSocket(BluetoothSocket socket, BluetoothDevice device) {
        String deviceNameForState = device.getAddress(); // 默认 (Default)
        String fetchedName = device.getName();
        if (fetchedName != null && !fetchedName.isEmpty()) {
            deviceNameForState = fetchedName;
        }
        Log.d(TAG, "manageConnectedSocket 已为 " + deviceNameForState + " 启动 (manageConnectedSocket started for " + deviceNameForState + ")");

        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }
        connectedThread = new ConnectedThread(socket);
        connectedThread.start();

        updateConnectionState(ConnectionState.STATE_CONNECTED, deviceNameForState);
    }

    private synchronized void updateConnectionState(int state, String deviceName) {
        handler.obtainMessage(MessageConstants.MESSAGE_STATE_CHANGE, state, -1, deviceName).sendToTarget();
    }

    private void connectionFailed() {
        Log.e(TAG, "连接失败 (Connection Failed)");
        Message msg = handler.obtainMessage(MessageConstants.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString("toast", "无法连接设备 (Unable to connect device)");
        msg.setData(bundle);
        handler.sendMessage(msg);
        updateConnectionState(ConnectionState.STATE_NONE, null);
    }

    private void connectionLost() {
        Log.e(TAG, "连接丢失 (Connection Lost)");
        Message msg = handler.obtainMessage(MessageConstants.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString("toast", "设备连接已丢失 (Device connection was lost)");
        msg.setData(bundle);
        handler.sendMessage(msg);
        updateConnectionState(ConnectionState.STATE_NONE, null);
    }


    @SuppressLint("MissingPermission") // Permissions are checked before discovery cancellation
    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(discoveryReceiver);
        if (bluetoothAdapter != null && checkPermission(getBluetoothScanPermission())) {
            if (bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }
        }
        if (connectThread != null) connectThread.cancel();
        if (connectedThread != null) connectedThread.cancel();
    }

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is checked inside constructor
    private class ConnectThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;

        public ConnectThread(BluetoothDevice device) {
            mmDevice = device;
            BluetoothSocket tmp = null;
            try {
                if (!checkPermission(getBluetoothConnectPermission())) {
                    throw new IOException("BLUETOOTH_CONNECT permission missing for socket creation");
                }
                tmp = device.createRfcommSocketToServiceRecord(MY_UUID);
            } catch (IOException e) {
                Log.e(TAG, "ConnectThread: 套接字创建失败 (Socket creation failed)", e);
                // 在构造函数中发生错误时，确保mmSocket为null，run()方法会处理
                // (When error occurs in constructor, ensure mmSocket is null, run() will handle)
                handler.post(MainActivity.this::connectionFailed); // 确保UI得到通知 (Ensure UI is notified)
            }
            mmSocket = tmp;
        }

        public void run() {
            if (mmSocket == null) { // 如果套接字创建失败 (If socket creation failed)
                Log.e(TAG, "ConnectThread: mmSocket 为空，无法连接。可能由于权限或IO错误。(mmSocket is null, cannot connect. Possibly due to permission or IO error.)");
                // connectionFailed() 应该已在构造函数中通过handler.post调用 (connectionFailed() should have been called via handler.post in constructor)
                return;
            }
            Log.i(TAG, "BEGIN mConnectThread, Device: " + mmDevice.getAddress());
            setName("ConnectThread-" + mmDevice.getAddress());

            if (checkPermission(getBluetoothScanPermission())) {
                if (bluetoothAdapter.isDiscovering()) {
                    bluetoothAdapter.cancelDiscovery();
                }
            } else {
                Log.w(TAG, "ConnectThread 中缺少扫描权限以取消发现。(Scan permission missing in ConnectThread to cancel discovery.)");
            }

            try {
                mmSocket.connect();
            } catch (IOException e) {
                Log.e(TAG, "ConnectThread: 连接失败 (Connection failed)", e);
                try {
                    mmSocket.close();
                } catch (IOException e2) {
                    Log.e(TAG, "ConnectThread: 关闭套接字失败 (Failed to close socket)", e2);
                }
                connectionFailed();
                return;
            }

            synchronized (MainActivity.this) {
                connectThread = null;
            }
            manageConnectedSocket(mmSocket, mmDevice);
        }

        public void cancel() {
            try {
                if (mmSocket != null) mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "ConnectThread: 关闭套接字失败 (Failed to close socket)", e);
            }
        }
    }

    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;
        private byte[] mmBuffer;

        public ConnectedThread(BluetoothSocket socket) {
            Log.d(TAG, "创建 ConnectedThread (Creating ConnectedThread)");
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "临时套接字未创建 (Temp sockets not created)", e);
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
            String remoteDeviceAddress = "UnknownDevice";
            if (socket.getRemoteDevice() != null) {
                remoteDeviceAddress = socket.getRemoteDevice().getAddress();
            }
            setName("ConnectedThread-" + remoteDeviceAddress);
        }

        public void run() {
            Log.i(TAG, "BEGIN mConnectedThread");
            mmBuffer = new byte[1024];
            int numBytes;

            while (mmSocket.isConnected()) {
                try {
                    numBytes = mmInStream.read(mmBuffer);
                    Message readMsg = handler.obtainMessage(MessageConstants.MESSAGE_READ, numBytes, -1, mmBuffer.clone());
                    readMsg.sendToTarget();
                } catch (IOException e) {
                    Log.d(TAG, "输入流已断开 (Input stream was disconnected)", e);
                    connectionLost();
                    break;
                }
            }
            Log.i(TAG, "END mConnectedThread, socket disconnected.");
        }

        public void write(byte[] bytes) {
            try {
                mmOutStream.write(bytes);
                Log.d(TAG, "数据已发送 (Data sent): " + new String(bytes));
            } catch (IOException e) {
                Log.e(TAG, "发送数据时发生错误 (Error occurred when sending data)", e);
                connectionLost();
            }
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "无法关闭连接套接字 (Could not close the connect socket)", e);
            }
        }
    }
}