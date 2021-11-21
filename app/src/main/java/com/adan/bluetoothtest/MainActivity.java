package com.adan.bluetoothtest;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.view.LayoutInflater;

import com.adan.bluetoothtest.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {
    private ActivityMainBinding activityMainBinding;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
//        setContentView(R.layout.activity_main);
        activityMainBinding=ActivityMainBinding.inflate(LayoutInflater.from(this));
        setContentView(activityMainBinding.getRoot());
    }
}