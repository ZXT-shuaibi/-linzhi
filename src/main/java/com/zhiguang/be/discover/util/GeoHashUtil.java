package com.zhiguang.be.discover.util;

/**
 * GeoHash 工具类。
 * 用于将经纬度编码为指定精度的 GeoHash 字符串，便于做位置分桶和缓存分段。
 */
public class GeoHashUtil {

    private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";
    private static final int[] BITS = {16, 8, 4, 2, 1};

    /**
     * 按指定精度对经纬度进行 GeoHash 编码。
     * 编码过程中会交替细分经度和纬度区间，最终输出固定长度的 Base32 字符串。
     *
     * @param lat 纬度，取值范围为 -90 到 90
     * @param lng 经度，取值范围为 -180 到 180
     * @param precision GeoHash 长度，值越大表示位置精度越高
     * @return 编码后的 GeoHash 字符串
     */
    public static String encode(double lat, double lng, int precision) {
        double[] latRange = {-90.0, 90.0};
        double[] lngRange = {-180.0, 180.0};
        StringBuilder geohash = new StringBuilder();
        boolean isEven = true;
        int bit = 0;
        int ch = 0;

        while (geohash.length() < precision) {
            double mid;
            if (isEven) {
                mid = (lngRange[0] + lngRange[1]) / 2;
                if (lng > mid) {
                    ch |= BITS[bit];
                    lngRange[0] = mid;
                } else {
                    lngRange[1] = mid;
                }
            } else {
                mid = (latRange[0] + latRange[1]) / 2;
                if (lat > mid) {
                    ch |= BITS[bit];
                    latRange[0] = mid;
                } else {
                    latRange[1] = mid;
                }
            }

            isEven = !isEven;
            if (bit < 4) {
                bit++;
            } else {
                geohash.append(BASE32.charAt(ch));
                bit = 0;
                ch = 0;
            }
        }
        return geohash.toString();
    }
}