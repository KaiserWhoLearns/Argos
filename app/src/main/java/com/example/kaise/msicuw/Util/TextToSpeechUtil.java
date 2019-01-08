package com.example.kaise.msicuw.Util;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.widget.Toast;

import java.util.Locale;

public class TextToSpeechUtil {
    // 哪个Application调用了此服务
    private Context mContext;
    // 单例模式参数
    private static TextToSpeechUtil singleton;
    // 系统语音播报类
    private TextToSpeech textToSpeech;
    // TTS实体是否创建成功参数
    private boolean isSuccess = true;


    /*
     * 用来获取TextToSpeechUtils实体的方法
     * @param context 当前application的context
     * @return 返回一个TextToSpeechUtils实体
     */
    public static TextToSpeechUtil getInstance(Context context) {
        // 单例模式的实现
        if (singleton == null) {
            synchronized (TextToSpeechUtil.class) {
                if (singleton == null) {
                    singleton = new TextToSpeechUtil(context);
                }
            }
        }
        return singleton;
    }

    // 构造器
    public TextToSpeechUtil(Context context) {
        mContext = context.getApplicationContext();

        // 创建一个源生TTS实体
        textToSpeech = new TextToSpeech(context, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    //设置音高和语速
                    textToSpeech.setPitch(1.0f);
                    textToSpeech.setSpeechRate(0.9f);

                    //检查是否支持英文
                    int result = textToSpeech.setLanguage(Locale.US);
                    boolean failToCreate = (result == TextToSpeech.LANG_MISSING_DATA ||
                            result == TextToSpeech.LANG_NOT_SUPPORTED);
                    Log.i("tts", "Support English --> " + failToCreate);
                    if(failToCreate){
                        isSuccess = false;
                    }
                }else{
                    // 提示与log错误
                    Toast.makeText(mContext, "Cann't create TestToSpeech object", Toast.LENGTH_SHORT);
                    Log.e("TTSUtils", "Cann't create TestToSpeech object");
                }
            }
        });
    }

    /*
     * 语音播报方法， 要播报的文字会被放入一个消息队列，按顺序一条条读出来
     * @text 要进行播报的文字
     ·  ····················································································*/
    public void speak(String text) {
        if(!isSuccess) {
            return;
        }
        if(textToSpeech != null) {
            textToSpeech.speak(text, TextToSpeech.QUEUE_ADD, null);
        }
    }

    public void stopSpeak() {
        if(textToSpeech != null) {
            textToSpeech.stop();
        }
    }

    // 暂停TTS和回收资源方法
    public void stop(){
        if(textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
    }
}
