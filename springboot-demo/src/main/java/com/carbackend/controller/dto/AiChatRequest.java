package com.carbackend.controller.dto;

/**
 * AI 对话请求体
 * 用于接收 POST /car/ai/chat 的 JSON 请求体 {"text":"xxx", "image":"xxx"}，
 * image 为 Base64 字符串，体积较大，必须放在请求体里，不能走 URL 参数
 */
public class AiChatRequest {

    // 用户输入的文字
    private String text;

    // 图片的 Base64 字符串，可选，不传时为 null
    private String image;

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }
}
