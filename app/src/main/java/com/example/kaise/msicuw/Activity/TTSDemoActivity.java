package com.example.kaise.msicuw.Activity;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import com.example.kaise.msicuw.R;
import com.example.kaise.msicuw.Util.TextToSpeechUtil;

public class TTSDemoActivity extends AppCompatActivity {

    private TextToSpeechUtil tts = null;
    int counter = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ttsdemo);
        Button button = (Button) findViewById(R.id.speak_btn);
        tts = TextToSpeechUtil.getInstance(TTSDemoActivity.this);
        button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if(v.getId() == R.id.speak_btn) {
                    counter++;
                    tts.speak("" + counter);
                }
            }
        });
    }

    protected void onStop() {
        super.onStop();
        tts.stop();
    }
}
