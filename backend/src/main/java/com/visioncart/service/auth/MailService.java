package com.visioncart.service.auth;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);
    private static final int CODE_EXPIRE_MINUTES = 5;

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:noreply@example.com}")
    private String fromEmail;

    public MailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendVerificationCode(String to, String code) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(to);
            helper.setSubject("VisionCart 验证码");

            // Pure HTML with inline styles — zero external resources, instant render
            String html = """
                    <!DOCTYPE html>
                    <html>
                    <head><meta charset="UTF-8"></head>
                    <body style="margin:0;padding:0;background:#f7f9f8;">
                      <div style="max-width:420px;margin:40px auto;background:#fff;border-radius:12px;padding:32px;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;">
                        <div style="text-align:center;margin-bottom:24px;">
                          <span style="font-size:40px;">🛒</span>
                          <h2 style="color:#0A7C66;margin:8px 0 0;">VisionCart</h2>
                        </div>
                        <p style="color:#333;font-size:15px;line-height:1.6;">您的验证码是：</p>
                        <div style="text-align:center;margin:20px 0;">
                          <span style="display:inline-block;background:#0A7C66;color:#fff;font-size:28px;font-weight:bold;letter-spacing:8px;padding:12px 32px;border-radius:8px;">%s</span>
                        </div>
                        <p style="color:#666;font-size:13px;line-height:1.6;">%d 分钟内有效，请勿泄露给他人。<br>如非本人操作，请忽略此邮件。</p>
                        <hr style="border:none;border-top:1px solid #eee;margin:24px 0;">
                        <p style="color:#999;font-size:11px;text-align:center;">AI 拍照识物 · 智能比价购物助手</p>
                      </div>
                    </body>
                    </html>
                    """.formatted(code, CODE_EXPIRE_MINUTES);

            helper.setText(html, true);
            mailSender.send(message);
            log.info("验证码邮件已发送: to={}", to);
        } catch (MailException | MessagingException e) {
            log.error("发送验证码邮件失败: to={}", to, e);
            throw new IllegalStateException("验证码邮件发送失败", e);
        }
    }
}
