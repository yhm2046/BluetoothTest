package com.adan.bluetoothtest;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
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
                    initializeBluetoothAndScan();
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
                        String deviceName = (String) msg.obj;
                        switch (msg.arg1) {
                            case ConnectionState.STATE_CONNECTED:
                                Toast.makeText(MainActivity.this, "已连接到 " + deviceName, Toast.LENGTH_SHORT).show();
                                break;
                            case ConnectionState.STATE_CONNECTING:
                                Toast.makeText(MainActivity.this, "正在连接 " + deviceName + "...", Toast.LENGTH_SHORT).show();
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
                Toast.makeText(this, "请先启用蓝牙", Toast.LENGTH_SHORT).show();
                return;
            }

            if (ActivityCompat.checkSelfPermission(this, getBluetoothScanPermission()) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "缺少扫描权限以在连接前取消发现。");
            } else if (bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
                Log.d(TAG, "在尝试连接前已取消发现。");
            }

            String deviceInfo = (String) parent.getItemAtPosition(position);
            if (deviceInfo.startsWith("---")) return;

            String deviceAddress = deviceInfo.substring(deviceInfo.lastIndexOf("\n") + 1);
            Log.d(TAG, "准备连接到设备: " + deviceAddress);

            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            connectToDevice(device);
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
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        } else {
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
            }
            enableBluetoothLauncher.launch(enableBtIntent);
        } else {
            Log.d(TAG, "蓝牙已启用。正在列出已配对设备并开始扫描...");
            listPairedDevices();
            startDiscovery();
        }
    }

    private void listPairedDevices() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;

        if (ActivityCompat.checkSelfPermission(this, getBluetoothConnectPermission()) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "缺少 BLUETOOTH_CONNECT 权限以列出已配对设备。", Toast.LENGTH_SHORT).show();
            Log.w(TAG, "listPairedDevices: BLUETOOTH_CONNECT permission missing.");
            return;
        }
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();

        if (!discoveredDevicesList.isEmpty() && !discoveredDevicesList.get(0).equals("--- 已配对设备 ---")) {
            discoveredDevicesList.clear();
        }

        if (discoveredDevicesList.isEmpty()) {
            discoveredDevicesList.add("--- 已配对设备 ---");
        }

        if (pairedDevices != null && pairedDevices.size() > 0) {
            for (BluetoothDevice device : pairedDevices) {
                String deviceName = device.getName();
                String deviceHardwareAddress = device.getAddress();
                String deviceInfo = (deviceName == null || deviceName.isEmpty() ? "未知设备 (已配对)" : deviceName) + "\n" + deviceHardwareAddress;

                boolean found = false;
                for (String existingDevice : discoveredDevicesList) {
                    if (existingDevice.endsWith(deviceHardwareAddress)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    discoveredDevicesList.add(deviceInfo);
                }
            }
        } else {
            Log.d(TAG, "没有已配对的设备");
        }
        listAdapter.notifyDataSetChanged();
    }

    private void startDiscovery() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;

        if (ActivityCompat.checkSelfPermission(this, getBluetoothScanPermission()) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "扫描权限缺失。", Toast.LENGTH_SHORT).show();
            Log.w(TAG, "startDiscovery: Scanning permissions missing.");
            return;
        }

        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
            Log.d(TAG, "已取消正在进行的扫描，开始新的扫描。");
        }

        ArrayList<String> tempPairedInfo = new ArrayList<>();
        boolean inPairedSection = false;
        for (String item : discoveredDevicesList) {
            if (item.equals("--- 已配对设备 ---")) {
                inPairedSection = true;
                tempPairedInfo.add(item);
            } else if (item.equals("--- 新设备 ---")) {
                inPairedSection = false;
            } else if (inPairedSection) {
                tempPairedInfo.add(item);
            }
        }
        discoveredDevicesList.clear();
        discoveredDevicesList.addAll(tempPairedInfo);

        binding.btnScan.setEnabled(false);  // 禁用扫描按钮

        boolean discoveryStarted = bluetoothAdapter.startDiscovery();
        if (discoveryStarted) {
            Log.d(TAG, "开始扫描新设备...");
            if (!discoveredDevicesList.contains("--- 新设备 ---")) {
                discoveredDevicesList.add("--- 新设备 ---");
            }
            listAdapter.notifyDataSetChanged();
        } else {
            Log.e(TAG, "BluetoothAdapter.startDiscovery() 返回 false。请检查权限和适配器状态。");
            binding.btnScan.setEnabled(true);  // 启用按钮以便用户重试
        }
    }

    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    String deviceName = "未知设备 (扫描)";
                    String deviceHardwareAddress = device.getAddress();

                    if (ActivityCompat.checkSelfPermission(MainActivity.this, getBluetoothConnectPermission()) == PackageManager.PERMISSION_GRANTED) {
                        String fetchedName = device.getName();
                        if (fetchedName != null && !fetchedName.isEmpty()) {
                            deviceName = fetchedName;
                        }
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
                            discoveredDevicesList.add("--- 新设备 ---");
                        }
                        discoveredDevicesList.add(deviceInfo);
                        listAdapter.notifyDataSetChanged();
                        Log.d(TAG, "发现新设备: " + deviceInfo);
                    }
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                Log.d(TAG, "Discovery started.");
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                Log.d(TAG, "Discovery finished.");
                binding.btnScan.setEnabled(true);  // 启用扫描按钮
                int deviceCount = countDevices();
                showRescanDialog(deviceCount);
            }
        }
    };

    // 统计设备数量（不包括标题行）
    private int countDevices() {
        int count = 0;
        for (String item : discoveredDevicesList) {
            if (!item.startsWith("---")) {
                count++;
            }
        }
        return count;
    }

    // 显示重新扫描提示框，包含设备数量
    private void showRescanDialog(int deviceCount) {
        String message = "已经扫描完成，共搜索到 " + deviceCount + " 个蓝牙设备，是否重新扫描？";
        new AlertDialog.Builder(this)
                .setTitle("扫描完成")
                .setMessage(message)
                .setPositiveButton("重新扫描", (dialog, which) -> startDiscovery())
                .setNegativeButton("取消", null)
                .show();
    }

    private String getBluetoothScanPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? Manifest.permission.BLUETOOTH_SCAN : Manifest.permission.BLUETOOTH_ADMIN;
    }

    private String getBluetoothConnectPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? Manifest.permission.BLUETOOTH_CONNECT : Manifest.permission.BLUETOOTH;
    }

    public synchronized void connectToDevice(BluetoothDevice device) {
        if (ActivityCompat.checkSelfPermission(this, getBluetoothConnectPermission()) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "缺少 BLUETOOTH_CONNECT 权限，无法连接。", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "connectToDevice: BLUETOOTH_CONNECT permission missing.");
            return;
        }

        String deviceNameForToast = device.getAddress();
        if (ActivityCompat.checkSelfPermission(this, getBluetoothConnectPermission()) == PackageManager.PERMISSION_GRANTED) {
            String fetchedName = device.getName();
            if (fetchedName != null && !fetchedName.isEmpty()) {
                deviceNameForToast = fetchedName;
            }
        }

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

    private synchronized void manageConnectedSocket(BluetoothSocket socket, BluetoothDevice device) {
        String deviceNameForState = device.getAddress();
        if (ActivityCompat.checkSelfPermission(this, getBluetoothConnectPermission()) == PackageManager.PERMISSION_GRANTED) {
            String fetchedName = device.getName();
            if (fetchedName != null && !fetchedName.isEmpty()) {
                deviceNameForState = fetchedName;
            }
        }
        Log.d(TAG, "manageConnectedSocket 已为 " + deviceNameForState + " 启动");

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
        Log.e(TAG, "连接失败");
        Message msg = handler.obtainMessage(MessageConstants.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString("toast", "无法连接设备");
        msg.setData(bundle);
        handler.sendMessage(msg);
        updateConnectionState(ConnectionState.STATE_NONE, null);
    }

    private void connectionLost() {
        Log.e(TAG, "连接丢失");
        Message msg = handler.obtainMessage(MessageConstants.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString("toast", "设备连接已丢失");
        msg.setData(bundle);
        handler.sendMessage(msg);
        updateConnectionState(ConnectionState.STATE_NONE, null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(discoveryReceiver);
        if (bluetoothAdapter != null && ActivityCompat.checkSelfPermission(this, getBluetoothScanPermission()) == PackageManager.PERMISSION_GRANTED) {
            if (bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }
        }
        if (connectThread != null) connectThread.cancel();
        if (connectedThread != null) connectedThread.cancel();
    }

    private class ConnectThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;

        public ConnectThread(BluetoothDevice device) {
            mmDevice = device;
            BluetoothSocket tmp = null;
            try {
                if (ActivityCompat.checkSelfPermission(MainActivity.this, getBluetoothConnectPermission()) != PackageManager.PERMISSION_GRANTED) {
                    throw new IOException("BLUETOOTH_CONNECT permission missing");
                }
                tmp = device.createRfcommSocketToServiceRecord(MY_UUID);
            } catch (IOException e) {
                Log.e(TAG, "ConnectThread: 套接字创建失败", e);
                handler.post(MainActivity.this::connectionFailed);
            }
            mmSocket = tmp;
        }

        public void run() {
            if (mmSocket == null) return;
            Log.i(TAG, "BEGIN mConnectThread, Device: " + mmDevice.getAddress());
            setName("ConnectThread-" + mmDevice.getAddress());

            if (ActivityCompat.checkSelfPermission(MainActivity.this, getBluetoothScanPermission()) == PackageManager.PERMISSION_GRANTED) {
                if (bluetoothAdapter.isDiscovering()) {
                    bluetoothAdapter.cancelDiscovery();
                }
            }

            try {
                mmSocket.connect();
            } catch (IOException e) {
                Log.e(TAG, "ConnectThread: 连接失败", e);
                try {
                    mmSocket.close();
                } catch (IOException e2) {
                    Log.e(TAG, "ConnectThread: 关闭套接字失败", e2);
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
                Log.e(TAG, "ConnectThread: 关闭套接字失败", e);
            }
        }
    }

    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;
        private byte[] mmBuffer;

        public ConnectedThread(BluetoothSocket socket) {
            Log.d(TAG, "创建 ConnectedThread");
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "临时套接字未创建", e);
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
            setName("ConnectedThread-" + (socket.getRemoteDevice() != null ? socket.getRemoteDevice().getAddress() : "Unknown"));
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
                    Log.d(TAG, "输入流已断开", e);
                    connectionLost();
                    break;
                }
            }
            Log.i(TAG, "END mConnectedThread, socket disconnected.");
        }

        public void write(byte[] bytes) {
            try {
                mmOutStream.write(bytes);
                Log.d(TAG, "数据已发送: " + new String(bytes));
            } catch (IOException e) {
                Log.e(TAG, "发送数据时发生错误", e);
                connectionLost();
            }
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "无法关闭连接套接字", e);
            }
        }
    }
}