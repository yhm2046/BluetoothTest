package com.adan.bluetoothtest;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {
    private ActivityMainBinding binding;
    private BluetoothAdapter bluetoothAdapter;
    private final ArrayList<String> discoveredDevicesList = new ArrayList<>();
    private ArrayAdapter<String> listAdapter;

    private ConnectThread connectThread;
    private ConnectedThread connectedThread;
    private long scanStartTime;
    private String connectedDeviceAddress = null;

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
            public void handleMessage(@NonNull Message msg) {
                switch (msg.what) {
                    case MessageConstants.MESSAGE_STATE_CHANGE:
                        String deviceNameForToast = (String) msg.obj;
                        switch (msg.arg1) {
                            case ConnectionState.STATE_CONNECTED:
                                Toast.makeText(MainActivity.this, "已连接到 " + deviceNameForToast, Toast.LENGTH_SHORT).show();
                                refreshDeviceListWithStatus();
                                break;
                            case ConnectionState.STATE_CONNECTING:
                                Toast.makeText(MainActivity.this, "正在连接 " + deviceNameForToast + "...", Toast.LENGTH_SHORT).show();
                                connectedDeviceAddress = null;
                                refreshDeviceListWithStatus();
                                break;
                            case ConnectionState.STATE_NONE:
                                Toast.makeText(MainActivity.this, deviceNameForToast != null ? deviceNameForToast : "未连接", Toast.LENGTH_SHORT).show();
                                connectedDeviceAddress = null;
                                refreshDeviceListWithStatus();
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

            String fullDeviceInfo = (String) parent.getItemAtPosition(position);
            if (fullDeviceInfo.startsWith("---") || fullDeviceInfo.equals("无已配对设备") || fullDeviceInfo.equals("未发现新设备")) {
                return;
            }

            String deviceAddress = fullDeviceInfo.substring(fullDeviceInfo.lastIndexOf("\n") + 1);
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);

            String displayNameFromList = fullDeviceInfo.substring(0, fullDeviceInfo.indexOf("\n"));
            displayNameFromList = displayNameFromList.replace(" (已连接)", "").replace(" (未连接)", "").replace(" (已配对)", "").replace(" (扫描)", "");

            if (checkPermission(getBluetoothScanPermission())) {
                if (bluetoothAdapter.isDiscovering()) {
                    bluetoothAdapter.cancelDiscovery();
                    Log.d(TAG, "Discovery cancelled before user interaction.");
                }
            }

            boolean isCurrentlyConnectedToThisDevice = (connectedDeviceAddress != null && connectedDeviceAddress.equals(deviceAddress));
            boolean isBonded = false;
            if (checkPermission(getBluetoothConnectPermission())) {
                isBonded = device.getBondState() == BluetoothDevice.BOND_BONDED;
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(displayNameFromList);

            ArrayList<String> dialogOptions = new ArrayList<>();
            if (isCurrentlyConnectedToThisDevice) {
                dialogOptions.add("断开连接");
            } else {
                dialogOptions.add("连接");
            }
            if (isBonded) {
                dialogOptions.add("取消配对");
            }
            dialogOptions.add("取消");

            builder.setItems(dialogOptions.toArray(new String[0]), (dialog, which) -> {
                String selectedOption = dialogOptions.get(which);
                switch (selectedOption) {
                    case "连接":
                        connectToDevice(device);
                        break;
                    case "断开连接":
                        if (connectedThread != null) {
                            connectedThread.cancel();
                        }
                        break;
                    case "取消配对":
                        unpairDevice(device);
                        break;
                    case "取消":
                        dialog.dismiss();
                        break;
                }
            });
            builder.show();
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

        IntentFilter bondFilter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        registerReceiver(bondStateReceiver, bondFilter);

        if (checkAndRequestPermissions()) {
            initializeBluetooth();
        }
    }

    /**
     * 优化 refreshDeviceListWithStatus 方法：
     * 确保在刷新时，分别独立更新“已配对设备”和“新设备”部分，而不是清空整个列表。
     * 避免重复清空 discoveredDevicesList，改为分别更新两个部分的设备列表。
     * <p>
     * 分离刷新逻辑：
     * 分别定义 updatePairedDevices 和 updateNewDevices 方法，分别处理已配对设备和新设备的列表更新。
     * 在 refreshDeviceListWithStatus 中调用这两个方法，确保每次刷新都能保留所有设备。
     */
    private void refreshDeviceListWithStatus() {
        // 备份新设备部分
        ArrayList<String> newDevices = new ArrayList<>();
        boolean newDevicesSectionStarted = false;
        for (String item : discoveredDevicesList) {
            if (item.equals("--- 新设备 ---")) {
                newDevicesSectionStarted = true;
                newDevices.add(item);
                continue;
            }
            if (newDevicesSectionStarted && !item.startsWith("---")) {
                newDevices.add(item);
            }
        }

        // 更新已配对设备部分
        updatePairedDevices();

        // 恢复新设备部分并更新状态
        updateNewDevices(newDevices);

        listAdapter.notifyDataSetChanged();
    }

    // 更新已配对设备部分
    @SuppressLint("MissingPermission")
    private void updatePairedDevices() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Log.w(TAG, "updatePairedDevices: Bluetooth not enabled or adapter null");
            discoveredDevicesList.clear();
            discoveredDevicesList.add("--- 已配对设备 ---");
            discoveredDevicesList.add("无已配对设备");
            return;
        }

        if (!checkPermission(getBluetoothConnectPermission())) {
            Toast.makeText(this, "缺少蓝牙连接权限以列出已配对设备。", Toast.LENGTH_SHORT).show();
            Log.w(TAG, "updatePairedDevices: BLUETOOTH_CONNECT permission missing.");
            discoveredDevicesList.clear();
            discoveredDevicesList.add("--- 已配对设备 ---");
            discoveredDevicesList.add("无已配对设备");
            return;
        }

        // 保留“新设备”部分
        ArrayList<String> newDevicesSection = new ArrayList<>();
        boolean newDevicesSectionStarted = false;
        for (String item : discoveredDevicesList) {
            if (item.equals("--- 新设备 ---")) {
                newDevicesSectionStarted = true;
                newDevicesSection.add(item);
                continue;
            }
            if (newDevicesSectionStarted) {
                newDevicesSection.add(item);
            }
        }

        // 清空列表并重新添加已配对设备
        discoveredDevicesList.clear();
        discoveredDevicesList.add("--- 已配对设备 ---");

        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        if (pairedDevices != null && !pairedDevices.isEmpty()) {
            for (BluetoothDevice device : pairedDevices) {
                String deviceName = device.getName();
                String deviceHardwareAddress = device.getAddress();
                String status = (connectedDeviceAddress != null && connectedDeviceAddress.equals(deviceHardwareAddress)) ? " (已连接)" : " (未连接)";
                String deviceInfo = (deviceName == null || deviceName.isEmpty() ? "未知设备" : deviceName) + status + "\n" + deviceHardwareAddress;
                discoveredDevicesList.add(deviceInfo);
            }
        } else {
            discoveredDevicesList.add("无已配对设备");
            Log.d(TAG, "没有已配对的设备");
        }

        // 恢复“新设备”部分
        if (!newDevicesSection.isEmpty()) {
            discoveredDevicesList.addAll(newDevicesSection);
        }
    }

    /**
     * 删除了 status 变量的拼接，仅保留 name + "\n" + address。
     */
    private void updateNewDevices(ArrayList<String> newDevices) {
        ArrayList<String> updatedNewDevices = new ArrayList<>();
        updatedNewDevices.add("--- 新设备 ---");

        for (String item : newDevices) {
            if (item.equals("--- 新设备 ---") || item.equals("未发现新设备")) {
                continue;
            }
            String address = item.substring(item.lastIndexOf("\n") + 1);
            BluetoothDevice dev = bluetoothAdapter.getRemoteDevice(address);
            String name = "未知设备";
            if (checkPermission(getBluetoothConnectPermission())) {
                name = dev.getName() == null || dev.getName().isEmpty() ? "未知设备" : dev.getName();
            }
            // 优化：移除状态字符串，仅显示名称和地址
            updatedNewDevices.add(name + "\n" + address);
        }

        if (updatedNewDevices.size() == 1) { // 只有标题，没有设备
            updatedNewDevices.add("未发现新设备");
        }

        // 移除旧的“新设备”部分（如果存在），并添加更新后的部分
        int newDevicesHeaderIndex = discoveredDevicesList.indexOf("--- 新设备 ---");
        if (newDevicesHeaderIndex != -1) {
            while (discoveredDevicesList.size() > newDevicesHeaderIndex) {
                discoveredDevicesList.remove(newDevicesHeaderIndex);
            }
        }
        discoveredDevicesList.addAll(updatedNewDevices);
    }  //end updateNewDevices

    private boolean checkAndRequestPermissions() {
        ArrayList<String> permissionsToRequest = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!checkPermission(Manifest.permission.BLUETOOTH_SCAN)) permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN);
            if (!checkPermission(Manifest.permission.BLUETOOTH_CONNECT)) permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            if (!checkPermission(Manifest.permission.BLUETOOTH)) permissionsToRequest.add(Manifest.permission.BLUETOOTH);
            if (!checkPermission(Manifest.permission.BLUETOOTH_ADMIN)) permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADMIN);
        }
        if (!checkPermission(Manifest.permission.ACCESS_FINE_LOCATION)) permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);

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
            if (!checkPermission(getBluetoothConnectPermission())) {
                Log.w(TAG, "缺少 BLUETOOTH_CONNECT 权限以执行 ACTION_REQUEST_ENABLE。");
            }
            enableBluetoothLauncher.launch(enableBtIntent);
        } else {
            Log.d(TAG, "蓝牙已启用。正在列出已配对设备并开始扫描...");
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
            if (!checkPermission(getBluetoothConnectPermission())) {
                Log.w(TAG, "缺少 BLUETOOTH_CONNECT 权限以执行 ACTION_REQUEST_ENABLE。");
            }
            enableBluetoothLauncher.launch(enableBtIntent);
        } else {
            listPairedDevices();
        }
    }

    @SuppressLint("MissingPermission")
    private void listPairedDevices() {
        updatePairedDevices();
        listAdapter.notifyDataSetChanged();
    }

    @SuppressLint("MissingPermission")
    private void startDiscovery() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Log.w(TAG, "startDiscovery: Bluetooth not enabled or adapter null");
            return;
        }
        if (!checkPermission(getBluetoothScanPermission()) || !checkPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            Toast.makeText(this, "扫描权限缺失。", Toast.LENGTH_SHORT).show();
            Log.w(TAG, "startDiscovery: Scanning permissions missing.");
            binding.btnScan.setEnabled(true);
            return;
        }

        scanStartTime = System.currentTimeMillis();
        binding.btnScan.setEnabled(false);
        Log.d(TAG, "扫描按钮已禁用");

        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
            Log.d(TAG, "已取消正在进行的扫描");
        }

        boolean discoveryStarted = bluetoothAdapter.startDiscovery();
        if (discoveryStarted) {
            Log.d(TAG, "开始扫描新设备...");
        } else {
            Log.e(TAG, "BluetoothAdapter.startDiscovery() 返回 false。");
            Toast.makeText(this, "启动发现失败。", Toast.LENGTH_SHORT).show();
            binding.btnScan.setEnabled(true);
        }
    }

    private boolean checkPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * deviceInfo 不再包含状态字符串，直接使用 deviceName + "\n" + deviceHardwareAddress。
     */
    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    String deviceName = "未知设备";
                    String deviceHardwareAddress = device.getAddress();

                    if (checkPermission(getBluetoothConnectPermission())) {
                        String fetchedName = device.getName();
                        if (fetchedName != null && !fetchedName.isEmpty()) {
                            deviceName = fetchedName;
                        }
                    }

                    // 优化：新设备不显示状态字符串
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
                        if (!discoveredDevicesList.get(existingIndex).equals(deviceInfo)) {
                            discoveredDevicesList.set(existingIndex, deviceInfo);
                            listAdapter.notifyDataSetChanged();
                            Log.d(TAG, "更新设备信息: " + deviceInfo);
                        }
                    } else {
                        int newDevicesHeaderIndex = discoveredDevicesList.indexOf("--- 新设备 ---");
                        if (newDevicesHeaderIndex == -1) {
                            discoveredDevicesList.add("--- 新设备 ---");
                            newDevicesHeaderIndex = discoveredDevicesList.size() - 1;
                        }
                        if (newDevicesHeaderIndex + 1 < discoveredDevicesList.size() &&
                                discoveredDevicesList.get(newDevicesHeaderIndex + 1).equals("未发现新设备")) {
                            discoveredDevicesList.remove(newDevicesHeaderIndex + 1);
                        }
                        discoveredDevicesList.add(deviceInfo);
                        listAdapter.notifyDataSetChanged();
                        Log.d(TAG, "发现新设备: " + deviceInfo);
                    }
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                Log.d(TAG, "Discovery started.");
                binding.btnScan.setEnabled(false);
                Log.d(TAG, "扫描按钮在 ACTION_DISCOVERY_STARTED 中被禁用");
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                Log.d(TAG, "Discovery finished.");
                binding.btnScan.setEnabled(true);
                Log.d(TAG, "扫描按钮在 ACTION_DISCOVERY_FINISHED 中被启用");

                int deviceCount = countDevices();
                long scanDuration = System.currentTimeMillis() - scanStartTime;
                int minutes = (int) (scanDuration / 60000);
                int seconds = (int) ((scanDuration % 60000) / 1000);
                showRescanDialog(deviceCount, minutes, seconds);
            }
        }
    };  // end discoveryReceiver
    /**
     * 添加 null 检查：
     * 使用 deviceIdentifier 变量存储最终显示的设备标识。
     *默认使用 device.getAddress() 作为回退值。 如果有权限且 device.getName() 不为 null 且不为空，则使用设备名称。
     * 确保消息清晰：
     * 无论设备名称是否可用，Toast 消息都会显示有意义的设备标识（名称或地址），避免出现“设备null”的情况。
     */
    private final BroadcastReceiver bondStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device == null) return;
                final int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                final int previousBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR);

                Log.d(TAG, "设备 " + device.getAddress() + " 绑定状态改变: " + previousBondState + " -> " + bondState);

                if (bondState == BluetoothDevice.BOND_BONDED || bondState == BluetoothDevice.BOND_NONE) {
                    // 修复：优先获取设备名称，若为 null 则使用设备地址
                    String deviceIdentifier = device.getAddress(); // 默认使用地址
                    if (checkPermission(getBluetoothConnectPermission())) {
                        String deviceName = device.getName();
                        if (deviceName != null && !deviceName.isEmpty()) {
                            deviceIdentifier = deviceName;
                        }
                    }
                    Toast.makeText(context, "设备 " + deviceIdentifier +
                            (bondState == BluetoothDevice.BOND_BONDED ? " 已配对" : " 已取消配对"), Toast.LENGTH_SHORT).show();
                    refreshDeviceListWithStatus();
                }
            }
        }
    }; //end BroadcastReceiver bondStateReceiver

    private int countDevices() {
        int count = 0;
        for (String item : discoveredDevicesList) {
            if (!item.startsWith("---") && !item.equals("无已配对设备") && !item.equals("未发现新设备")) {
                count++;
            }
        }
        return count;
    }

    @SuppressLint("DefaultLocale")
    private void showRescanDialog(int deviceCount, int minutes, int seconds) {
        @SuppressLint("DefaultLocale") String message;
        if (minutes == 0) {
            message = String.format("扫描完成，共搜索到%d个设备，耗时%d秒，是否重新扫描？", deviceCount, seconds);
        } else {
            message = String.format("扫描完成，共搜索到%d个设备，耗时%d分%d秒，是否重新扫描？", deviceCount, minutes, seconds);
        }
        new AlertDialog.Builder(this)
                .setTitle("扫描完成")
                .setMessage(message)
                .setPositiveButton("重新扫描", (dialog, which) -> {
                    if (checkAndRequestPermissions()) {
                        startDiscovery();
                    }
                })
                .setNegativeButton("取消", null)
                .setCancelable(false)
                .show();
    }

    private String getBluetoothScanPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? Manifest.permission.BLUETOOTH_SCAN : Manifest.permission.BLUETOOTH_ADMIN;
    }

    private String getBluetoothConnectPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? Manifest.permission.BLUETOOTH_CONNECT : Manifest.permission.BLUETOOTH;
    }

    @SuppressLint("MissingPermission")
    private void unpairDevice(BluetoothDevice device) {
        if (!checkPermission(getBluetoothConnectPermission())) {
            Toast.makeText(this, "缺少蓝牙连接权限以取消配对", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "unpairDevice: BLUETOOTH_CONNECT permission missing.");
            return;
        }
        try {
            Method method = device.getClass().getMethod("removeBond", (Class[]) null);
            boolean success = (boolean) method.invoke(device, (Object[]) null);
            if (success) {
                Toast.makeText(this, "正在取消配对: " + (device.getName() != null ? device.getName() : device.getAddress()), Toast.LENGTH_SHORT).show();
            } else {
                Log.e(TAG, "removeBond() 调用返回 false for " + device.getAddress());
                Toast.makeText(this, "取消配对 " + (device.getName() != null ? device.getName() : device.getAddress()) + " 失败", Toast.LENGTH_LONG).show();
                refreshDeviceListWithStatus();
            }
        } catch (Exception e) {
            Log.e(TAG, "取消配对失败 " + (device.getName() != null ? device.getName() : device.getAddress()), e);
            Toast.makeText(this, "取消配对 " + (device.getName() != null ? device.getName() : device.getAddress()) + " 失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            refreshDeviceListWithStatus();
        }
    }

    @SuppressLint("MissingPermission")
    public synchronized void connectToDevice(BluetoothDevice device) {
        if (!checkPermission(getBluetoothConnectPermission())) {
            Toast.makeText(this, "缺少蓝牙连接权限，无法连接。", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "connectToDevice: BLUETOOTH_CONNECT permission missing.");
            return;
        }

        String deviceNameForToast = device.getAddress();
        String fetchedName = device.getName();
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

    @SuppressLint("MissingPermission")
    private synchronized void manageConnectedSocket(BluetoothSocket socket, BluetoothDevice device) {
        String deviceNameForState = device.getAddress();
        String fetchedName = device.getName();
        if (fetchedName != null && !fetchedName.isEmpty()) {
            deviceNameForState = fetchedName;
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

        connectedDeviceAddress = device.getAddress();
        connectedThread = new ConnectedThread(socket);
        connectedThread.start();

        updateConnectionState(ConnectionState.STATE_CONNECTED, deviceNameForState);
    }

    private synchronized void updateConnectionState(int state, String deviceName) {
        if (state == ConnectionState.STATE_NONE || state == ConnectionState.STATE_LISTEN) {
            connectedDeviceAddress = null;
        }
        Message msg = handler.obtainMessage(MessageConstants.MESSAGE_STATE_CHANGE, state, -1, deviceName);
        if (state == ConnectionState.STATE_NONE && deviceName == null) {
            msg.obj = "连接已断开";
        }
        msg.sendToTarget();
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

    @SuppressLint("MissingPermission")
    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(discoveryReceiver);
        unregisterReceiver(bondStateReceiver);
        if (bluetoothAdapter != null && checkPermission(getBluetoothScanPermission())) {
            if (bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }
        }
        if (connectThread != null) connectThread.cancel();
        if (connectedThread != null) connectedThread.cancel();
    }

    @SuppressLint("MissingPermission")
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
                Log.e(TAG, "ConnectThread: 套接字创建失败", e);
                final String errorMsg = e.getMessage();
                handler.post(() -> {
                    if (errorMsg != null && errorMsg.contains("permission missing")) {
                        Toast.makeText(MainActivity.this, "连接失败: 缺少蓝牙连接权限", Toast.LENGTH_LONG).show();
                    }
                    connectionFailed();
                });
            }
            mmSocket = tmp;
        }

        public void run() {
            if (mmSocket == null) {
                Log.e(TAG, "ConnectThread: mmSocket 为空，无法连接。");
                return;
            }
            Log.i(TAG, "BEGIN mConnectThread, Device: " + mmDevice.getAddress());
            setName("ConnectThread-" + mmDevice.getAddress());

            if (checkPermission(getBluetoothScanPermission())) {
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
            String remoteDeviceAddress = "UnknownDevice";
            try {
                if (socket.getRemoteDevice() != null) {
                    remoteDeviceAddress = socket.getRemoteDevice().getAddress();
                }
            } catch (Exception e) {
                Log.w(TAG, "Error getting remote device address in ConnectedThread constructor", e);
            }
            setName("ConnectedThread-" + remoteDeviceAddress);
        }

        public void run() {
            Log.i(TAG, "BEGIN mConnectedThread");
            byte[] mmBuffer = new byte[1024];
            int numBytes;

            while (mmSocket.isConnected()) {
                try {
                    if (mmInStream == null) throw new IOException("InputStream is null");
                    numBytes = mmInStream.read(mmBuffer);
                    Message readMsg = handler.obtainMessage(MessageConstants.MESSAGE_READ, numBytes, -1, mmBuffer.clone());
                    readMsg.sendToTarget();
                } catch (IOException e) {
                    Log.d(TAG, "输入流已断开或读取错误", e);
                    connectionLost();
                    break;
                }
            }
            Log.i(TAG, "END mConnectedThread, socket disconnected.");
        }

        public void write(byte[] bytes) {
            try {
                if (mmOutStream == null) throw new IOException("OutputStream is null");
                mmOutStream.write(bytes);
                Log.d(TAG, "数据已发送: " + new String(bytes));
            } catch (IOException e) {
                Log.e(TAG, "发送数据时发生错误", e);
                connectionLost();
            }
        }

        public void cancel() {
            try {
                if (mmSocket != null) mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "无法关闭连接套接字", e);
            }
        }
    }
}