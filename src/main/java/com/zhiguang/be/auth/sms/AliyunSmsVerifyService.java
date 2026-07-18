package com.zhiguang.be.auth.sms;

import com.aliyun.dypnsapi20170525.Client;
import com.aliyun.dypnsapi20170525.models.CheckSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.CheckSmsVerifyCodeResponse;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import com.aliyun.teaopenapi.models.Config;
import com.zhiguang.be.common.exception.BusinessException;
import com.zhiguang.be.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;

@Service
public class AliyunSmsVerifyService {

    private static final String DEFAULT_COUNTRY_CODE = "86";
    private static final String VERIFY_PASS = "PASS";

    private final SmsProperties props;
    private volatile Client client;

    public AliyunSmsVerifyService(SmsProperties props) {
        this.props = props;
    }

    public boolean isEnabled() {
        return props.isEnabled();
    }

    public String send(String phone, int codeLength, Duration validTime, Duration interval) {
        if (!props.isEnabled()) {
            return null;
        }

        validateConfigured();
        try {
            SendSmsVerifyCodeRequest request = new SendSmsVerifyCodeRequest()
                    .setPhoneNumber(phone)
                    .setCountryCode(countryCode())
                    .setSignName(props.getSignName())
                    .setTemplateCode(props.getTemplateCode())
                    .setTemplateParam(templateParam(validTime))
                    .setCodeType(1L)
                    .setCodeLength((long) safeCodeLength(codeLength))
                    .setValidTime(Math.max(1L, safeSeconds(validTime)))
                    .setInterval(Math.max(0L, safeSeconds(interval)))
                    .setDuplicatePolicy(1L)
                    .setReturnVerifyCode(props.isExposeCode());
            applySchemeName(request);

            SendSmsVerifyCodeResponse response = client().sendSmsVerifyCode(request);
            if (response.body == null || !Boolean.TRUE.equals(response.body.success)) {
                String msg = response.body != null ? response.body.message : "短信验证码发送失败";
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, msg);
            }
            if (props.isExposeCode() && response.body.model != null) {
                return response.body.model.verifyCode;
            }
            return null;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "短信验证码发送失败：" + e.getMessage());
        }
    }

    public boolean verify(String phone, String code) {
        if (!props.isEnabled()) {
            return false;
        }

        validateConfigured();
        try {
            CheckSmsVerifyCodeRequest request = new CheckSmsVerifyCodeRequest()
                    .setPhoneNumber(phone)
                    .setCountryCode(countryCode())
                    .setVerifyCode(code);
            applySchemeName(request);

            CheckSmsVerifyCodeResponse response = client().checkSmsVerifyCode(request);
            if (response.body == null || !Boolean.TRUE.equals(response.body.success)) {
                String msg = response.body != null ? response.body.message : "短信验证码校验失败";
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, msg);
            }
            return response.body.model != null && VERIFY_PASS.equals(response.body.model.verifyResult);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "短信验证码校验失败：" + e.getMessage());
        }
    }

    public boolean shouldExposeCode() {
        return props.isExposeCode();
    }

    private synchronized Client client() throws Exception {
        if (client != null) {
            return client;
        }
        Config config = new Config()
                .setAccessKeyId(resolveCredential(props.getAccessKeyId(), "ALIBABA_CLOUD_ACCESS_KEY_ID"))
                .setAccessKeySecret(resolveCredential(props.getAccessKeySecret(), "ALIBABA_CLOUD_ACCESS_KEY_SECRET"))
                .setEndpoint(props.getEndpoint());
        client = new Client(config);
        return client;
    }

    private void validateConfigured() {
        if (!StringUtils.hasText(resolveCredential(props.getAccessKeyId(), "ALIBABA_CLOUD_ACCESS_KEY_ID"))
                || !StringUtils.hasText(resolveCredential(props.getAccessKeySecret(), "ALIBABA_CLOUD_ACCESS_KEY_SECRET"))
                || !StringUtils.hasText(props.getEndpoint())
                || !StringUtils.hasText(props.getSignName())
                || !StringUtils.hasText(props.getTemplateCode())) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "短信认证服务配置不完整");
        }
    }

    private void applySchemeName(SendSmsVerifyCodeRequest request) {
        if (StringUtils.hasText(props.getSchemeName())) {
            request.setSchemeName(props.getSchemeName());
        }
    }

    private void applySchemeName(CheckSmsVerifyCodeRequest request) {
        if (StringUtils.hasText(props.getSchemeName())) {
            request.setSchemeName(props.getSchemeName());
        }
    }

    private String countryCode() {
        return StringUtils.hasText(props.getCountryCode()) ? props.getCountryCode() : DEFAULT_COUNTRY_CODE;
    }

    private int safeCodeLength(int codeLength) {
        return Math.min(Math.max(codeLength, 4), 8);
    }

    private String templateParam(Duration validTime) {
        long minutes = Math.max(1L, (safeSeconds(validTime) + 59L) / 60L);
        return "{\"code\":\"##code##\",\"min\":\"" + minutes + "\"}";
    }

    private long safeSeconds(Duration duration) {
        if (duration == null || duration.isNegative()) {
            return 0L;
        }
        return duration.toSeconds();
    }

    private String resolveCredential(String configured, String envName) {
        if (StringUtils.hasText(configured)) {
            return configured;
        }
        return System.getenv(envName);
    }
}
