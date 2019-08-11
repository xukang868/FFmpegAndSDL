package com.xiaohan.ffmpegdecoder;

/**
 * Created by xiaohan on 2019/7/26
 * Describe:
 */
public class FFmepegDecoder {
    public static  native int decode(String inputurl, String outputurl);

    static{
        System.loadLibrary("FFmpegDecoder");
    }
}
