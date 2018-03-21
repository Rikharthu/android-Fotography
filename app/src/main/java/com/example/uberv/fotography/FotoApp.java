package com.example.uberv.fotography;

import android.app.Application;
import android.util.Log;

import com.example.uberv.fotography.util.DevelopmentTree;

import timber.log.Timber;


public class FotoApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        Timber.plant(new DevelopmentTree());
    }
}
