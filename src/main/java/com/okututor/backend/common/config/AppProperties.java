package com.okututor.backend.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Cors cors = new Cors();
    private String frontendUrl;
    private Jwt jwt = new Jwt();
    private Livekit livekit = new Livekit();
    private Mail mail = new Mail();
    private RateLimit rateLimit = new RateLimit();
    private Seed seed = new Seed();
    private Storage storage = new Storage();
    private Media media = new Media();
    private Lesson lesson = new Lesson();

    public Cors getCors() { return cors; }
    public void setCors(Cors cors) { this.cors = cors; }
    public String getFrontendUrl() { return frontendUrl; }
    public void setFrontendUrl(String frontendUrl) { this.frontendUrl = frontendUrl; }
    public Jwt getJwt() { return jwt; }
    public void setJwt(Jwt jwt) { this.jwt = jwt; }
    public Livekit getLivekit() { return livekit; }
    public void setLivekit(Livekit livekit) { this.livekit = livekit; }
    public Mail getMail() { return mail; }
    public void setMail(Mail mail) { this.mail = mail; }
    public RateLimit getRateLimit() { return rateLimit; }
    public void setRateLimit(RateLimit rateLimit) { this.rateLimit = rateLimit; }
    public Seed getSeed() { return seed; }
    public void setSeed(Seed seed) { this.seed = seed; }
    public Storage getStorage() { return storage; }
    public void setStorage(Storage storage) { this.storage = storage; }
    public Media getMedia() { return media; }
    public void setMedia(Media media) { this.media = media; }
    public Lesson getLesson() { return lesson; }
    public void setLesson(Lesson lesson) { this.lesson = lesson; }

    public static class Cors {
        private java.util.List<String> allowedOrigins = java.util.List.of("http://localhost:5173");
        public java.util.List<String> getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(java.util.List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }
    }

    public static class Jwt {
        private String secret;
        private int accessTtlMinutes = 15;
        private int refreshTtlDays = 30;
        private int refreshGraceSeconds = 30;
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public int getAccessTtlMinutes() { return accessTtlMinutes; }
        public void setAccessTtlMinutes(int v) { this.accessTtlMinutes = v; }
        public int getRefreshTtlDays() { return refreshTtlDays; }
        public void setRefreshTtlDays(int v) { this.refreshTtlDays = v; }
        public int getRefreshGraceSeconds() { return refreshGraceSeconds; }
        public void setRefreshGraceSeconds(int v) { this.refreshGraceSeconds = v; }
    }

    public static class Livekit {
        private String apiKey;
        private String apiSecret;
        private String wsUrl;
        private int tokenTtlMinutes = 30;
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getApiSecret() { return apiSecret; }
        public void setApiSecret(String apiSecret) { this.apiSecret = apiSecret; }
        public String getWsUrl() { return wsUrl; }
        public void setWsUrl(String wsUrl) { this.wsUrl = wsUrl; }
        public int getTokenTtlMinutes() { return tokenTtlMinutes; }
        public void setTokenTtlMinutes(int v) { this.tokenTtlMinutes = v; }
    }

    public static class Mail {
        private boolean enabled = false;
        private String from = "Okututor <no-reply@okututor.local>";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }
    }

    public static class RateLimit {
        private boolean enabled = true;
        private boolean useRedis = false;
        private int loginPerMinute = 10;
        private int registerPerHour = 20;
        private int verifyPerHour = 30;
        private int resendPerMinute = 1;
        private int forgotPasswordPerHour = 10;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isUseRedis() { return useRedis; }
        public void setUseRedis(boolean useRedis) { this.useRedis = useRedis; }
        public int getLoginPerMinute() { return loginPerMinute; }
        public void setLoginPerMinute(int v) { this.loginPerMinute = v; }
        public int getRegisterPerHour() { return registerPerHour; }
        public void setRegisterPerHour(int v) { this.registerPerHour = v; }
        public int getVerifyPerHour() { return verifyPerHour; }
        public void setVerifyPerHour(int v) { this.verifyPerHour = v; }
        public int getResendPerMinute() { return resendPerMinute; }
        public void setResendPerMinute(int v) { this.resendPerMinute = v; }
        public int getForgotPasswordPerHour() { return forgotPasswordPerHour; }
        public void setForgotPasswordPerHour(int v) { this.forgotPasswordPerHour = v; }
    }

    public static class Seed {
        private boolean enabled = true;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class Storage {
        private String localDir = "./data/uploads";
        private String publicBaseUrl = "/api/v1/files";
        public String getLocalDir() { return localDir; }
        public void setLocalDir(String localDir) { this.localDir = localDir; }
        public String getPublicBaseUrl() { return publicBaseUrl; }
        public void setPublicBaseUrl(String publicBaseUrl) { this.publicBaseUrl = publicBaseUrl; }
    }

    /** медиа-хранилище: оптимизация изображений + object storage (local | r2). */
    public static class Media {
        /** local (дефолт, файлы на диске) или r2 (Cloudflare R2 + CDN). */
        private String provider = "local";
        private String format = "webp";
        private int avatarMaxWidth = 512;
        private int avatarMaxHeight = 512;
        private int avatarQuality = 82;
        private int courseCoverMaxWidth = 1600;
        private int courseCoverMaxHeight = 900;
        private int courseCoverQuality = 80;
        private int profileMaxWidth = 1200;
        private int profileMaxHeight = 1200;
        private int profileQuality = 82;
        private long maxAvatarSize = 5_242_880L;       // 5 MB
        private long maxCourseCoverSize = 10_485_760L; // 10 MB
        private long maxProfileSize = 10_485_760L;     // 10 MB
        /** image bomb: максимум декодируемых пикселей */
        private long maxPixels = 25_000_000L;
        /** абсолютный предел стороны изображения */
        private int maxDimension = 12000;
        /** grace period для orphan-очистки */
        private java.time.Duration orphanGrace = java.time.Duration.ofHours(24);
        private boolean orphanCleanupEnabled = false;
        private R2 r2 = new R2();

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getFormat() { return format; }
        public void setFormat(String format) { this.format = format; }
        public int getAvatarMaxWidth() { return avatarMaxWidth; }
        public void setAvatarMaxWidth(int v) { this.avatarMaxWidth = v; }
        public int getAvatarMaxHeight() { return avatarMaxHeight; }
        public void setAvatarMaxHeight(int v) { this.avatarMaxHeight = v; }
        public int getAvatarQuality() { return avatarQuality; }
        public void setAvatarQuality(int v) { this.avatarQuality = v; }
        public int getCourseCoverMaxWidth() { return courseCoverMaxWidth; }
        public void setCourseCoverMaxWidth(int v) { this.courseCoverMaxWidth = v; }
        public int getCourseCoverMaxHeight() { return courseCoverMaxHeight; }
        public void setCourseCoverMaxHeight(int v) { this.courseCoverMaxHeight = v; }
        public int getCourseCoverQuality() { return courseCoverQuality; }
        public void setCourseCoverQuality(int v) { this.courseCoverQuality = v; }
        public int getProfileMaxWidth() { return profileMaxWidth; }
        public void setProfileMaxWidth(int v) { this.profileMaxWidth = v; }
        public int getProfileMaxHeight() { return profileMaxHeight; }
        public void setProfileMaxHeight(int v) { this.profileMaxHeight = v; }
        public int getProfileQuality() { return profileQuality; }
        public void setProfileQuality(int v) { this.profileQuality = v; }
        public long getMaxAvatarSize() { return maxAvatarSize; }
        public void setMaxAvatarSize(long v) { this.maxAvatarSize = v; }
        public long getMaxCourseCoverSize() { return maxCourseCoverSize; }
        public void setMaxCourseCoverSize(long v) { this.maxCourseCoverSize = v; }
        public long getMaxProfileSize() { return maxProfileSize; }
        public void setMaxProfileSize(long v) { this.maxProfileSize = v; }
        public long getMaxPixels() { return maxPixels; }
        public void setMaxPixels(long v) { this.maxPixels = v; }
        public int getMaxDimension() { return maxDimension; }
        public void setMaxDimension(int v) { this.maxDimension = v; }
        public java.time.Duration getOrphanGrace() { return orphanGrace; }
        public void setOrphanGrace(java.time.Duration v) { this.orphanGrace = v; }
        public boolean isOrphanCleanupEnabled() { return orphanCleanupEnabled; }
        public void setOrphanCleanupEnabled(boolean v) { this.orphanCleanupEnabled = v; }
        public R2 getR2() { return r2; }
        public void setR2(R2 r2) { this.r2 = r2; }
    }

    /** окно входа в видеоурок относительно расписания брони (UTC). */
    public static class Lesson {
        private int joinMinutesBefore = 15;
        private int joinMinutesAfter = 60;
        public int getJoinMinutesBefore() { return joinMinutesBefore; }
        public void setJoinMinutesBefore(int v) { this.joinMinutesBefore = v; }
        public int getJoinMinutesAfter() { return joinMinutesAfter; }
        public void setJoinMinutesAfter(int v) { this.joinMinutesAfter = v; }
    }

    public static class R2 {
        private String accountId;
        private String accessKeyId;
        private String secretAccessKey;
        private String bucket;
        /** публичная база CDN/дев-URL бакета, напр. https://cdn.example.com */
        private String publicBaseUrl;
        public String getAccountId() { return accountId; }
        public void setAccountId(String accountId) { this.accountId = accountId; }
        public String getAccessKeyId() { return accessKeyId; }
        public void setAccessKeyId(String accessKeyId) { this.accessKeyId = accessKeyId; }
        public String getSecretAccessKey() { return secretAccessKey; }
        public void setSecretAccessKey(String secretAccessKey) { this.secretAccessKey = secretAccessKey; }
        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }
        public String getPublicBaseUrl() { return publicBaseUrl; }
        public void setPublicBaseUrl(String publicBaseUrl) { this.publicBaseUrl = publicBaseUrl; }
    }
}
