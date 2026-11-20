package org.askesis.zettel;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Precisa vir antes do super: é aí que a ponte do Capacitor é montada.
        registerPlugin(AskesisFsPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
