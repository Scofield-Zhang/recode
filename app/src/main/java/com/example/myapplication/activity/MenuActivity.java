package com.example.myapplication.activity;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.Button;

import com.example.myapplication.R;

public class MenuActivity extends AppCompatActivity implements View.OnClickListener {


    private Button record;
    private Button play;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_menu);
        initView();
    }

    private void initView() {
        record = (Button) findViewById(R.id.record);
        play = (Button) findViewById(R.id.play);

        record.setOnClickListener(this);
        play.setOnClickListener(this);
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.record:
                openActivity(MainActivity.class);
                break;
            case R.id.play:
                openActivity(PlayMovieActivity.class);
                break;
        }
    }

    private void openActivity(Class clazz) {
        startActivity(new Intent(this, clazz));
    }
}
