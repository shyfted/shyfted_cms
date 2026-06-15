package com.geniatech.el133sdk;

interface EpdManager {
    String getServiceVersion();
    int sendImage(String path);
    int sendImageByNum(String path, int screenNum);
}
