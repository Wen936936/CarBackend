package com.carbackend.core;

import com.carbackend.controller.CameraWebSocketController;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * K230 RTSP 图像流服务
 * 负责用 JavaCV 拉取 RTSP 流、逐帧解码成 JPEG、编码成 Base64，
 * 再交给 CameraWebSocketController 广播给所有已连接的 App 端。
 * 断线后每 3 秒自动重连一次。
 */
@Service
public class RtspStreamService {

    // K230 RTSP 流地址，例如 rtsp://localhost:8554/k230
    @Value("${k230.rtsp.url}")
    private String rtspUrl;

    // JPEG 编码后的 base64 字符串交给它广播给 App 端
    @Autowired
    private CameraWebSocketController cameraWebSocketController;

    // 断线重连间隔（毫秒）
    private static final int RECONNECT_INTERVAL = 3000;

    // 拉流线程是否继续运行，@PreDestroy 时置为 false 让它自然退出
    private volatile boolean running = true;

    // 拉流线程，随项目启动，作为守护线程避免阻塞进程退出
    private Thread streamThread;

    /**
     * 项目启动时开启拉流线程
     */
    @PostConstruct
    public void start() {
        streamThread = new Thread(this::pullLoop, "k230-rtsp-stream");
        // 设为守护线程，主程序退出时不残留
        streamThread.setDaemon(true);
        streamThread.start();
        System.out.println("【RTSP拉流服务启动】地址：" + rtspUrl);
    }

    /**
     * 项目关闭时停止拉流线程
     */
    @PreDestroy
    public void stop() {
        running = false;
        if (streamThread != null) {
            streamThread.interrupt();
        }
        System.out.println("【RTSP拉流服务停止】");
    }

    /**
     * 拉流主循环：正常情况下持续抓帧并推送；
     * 出现异常（流断开、地址不可达等）时清理资源，等待 3 秒后自动重连
     */
    private void pullLoop() {
        while (running) {
            try {
                // ffmpeg 解码器包装成帧采集器
                pullAndPush();
            } catch (Exception e) {
                System.out.println("【RTSP拉流异常】" + e.getMessage());
            }
            // 一轮结束（异常退出或流自然结束），未停止则等待后重连
            if (running) {
                System.out.println("【RTSP准备重连】" + RECONNECT_INTERVAL + " 毫秒后重试");
                try {
                    Thread.sleep(RECONNECT_INTERVAL);
                } catch (InterruptedException ie) {
                    // 被 stop() 打断，恢复中断标记后退出循环
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * 真正拉取 RTSP 流并逐帧推送
     * 无客户端连接时也会持续解码，保证 App 一连上就能收到画面
     */
    private void pullAndPush() throws Exception {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(rtspUrl);
        // 低延迟关键参数：TCP 传输避免丢包花屏、不缓冲、低延迟、不额外等待
        grabber.setOption("rtsp_transport", "tcp");
        grabber.setOption("fflags", "nobuffer");
        grabber.setOption("flags", "low_delay");
        grabber.setOption("max_delay", "0");

        // Frame 与 BufferedImage 之间的转换器
        Java2DFrameConverter converter = new Java2DFrameConverter();

        try {
            grabber.start();
            System.out.println("【RTSP连接成功】" + rtspUrl);

            Frame frame;
            while (running && (frame = grabber.grabImage()) != null) {
                BufferedImage image = converter.getBufferedImage(frame);
                if (image == null) {
                    continue;
                }
                // 编码成 JPEG 再转 Base64，交给 WebSocket 控制器广播
                String base64Image = encodeToBase64Jpeg(image);
                if (base64Image != null) {
                    cameraWebSocketController.broadcastImage(base64Image);
                }
            }
        } catch (Exception e) {
            // 拉流过程中的异常统一在这里打印，由外层循环负责重连
            System.out.println("【RTSP解码失败】" + e.getMessage());
        } finally {
            // 无论正常结束还是异常退出，都必须释放采集器，否则重连会占用句柄
            closeQuietly(grabber, converter);
        }
    }

    /**
     * 将 BufferedImage 编码成 JPEG 字节，再转为 Base64 字符串
     * 编码失败返回 null，避免影响拉流线程
     *
     * @param image 解码得到的图像
     * @return Base64 字符串，失败返回 null
     */
    private String encodeToBase64Jpeg(BufferedImage image) {
        try {
            // JPEG 不支持透明通道，带 alpha 的图先转成 BGR 三通道，否则编码报错
            BufferedImage target = image;
            if (image.getType() != BufferedImage.TYPE_3BYTE_BGR) {
                target = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
                target.getGraphics().drawImage(image, 0, 0, null);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            boolean ok = ImageIO.write(target, "jpg", out);
            if (!ok) {
                System.out.println("【图像编码失败】没有找到 JPEG 编码器");
                return null;
            }
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            System.out.println("【图像编码异常】" + e.getMessage());
            return null;
        }
    }

    /**
     * 安全关闭解码器与转换器，释放失败只打印日志不影响后续重连
     */
    private void closeQuietly(FFmpegFrameGrabber grabber, Java2DFrameConverter converter) {
        try {
            if (grabber != null) {
                grabber.stop();
                grabber.release();
            }
        } catch (Exception e) {
            System.out.println("【释放RTSP解码器失败】" + e.getMessage());
        }
        try {
            if (converter != null) {
                converter.close();
            }
        } catch (Exception e) {
            System.out.println("【释放帧转换器失败】" + e.getMessage());
        }
    }
}
