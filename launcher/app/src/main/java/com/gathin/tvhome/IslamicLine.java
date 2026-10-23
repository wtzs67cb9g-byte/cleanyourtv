package com.gathin.tvhome;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Two lines under the clock: today's Hijri date (Umm al-Qura) and today's five prayer times, with the next
 * prayer highlighted. Everything is computed on the TV: no network, no permissions.
 */
final class IslamicLine {

    // ---- Your city. Change these three and rebuild. ----
    static final double LATITUDE = 24.7136;   // Riyadh
    static final double LONGITUDE = 46.6753;
    static final String CITY = "الرياض";

    // Umm al-Qura: Fajr at 18.5 degrees, Isha 90 minutes after Maghrib (120 in Ramadan), Asr standard.
    private static final double FAJR_ANGLE = 18.5;
    private static final int NEXT_COLOR = 0xFFFFD27A;

    private static final String[] MONTHS = {"محرم", "صفر", "ربيع الأول", "ربيع الآخر", "جمادى الأولى",
            "جمادى الآخرة", "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذو الحجة"};
    private static final String[] NAMES = {"الفجر", "الظهر", "العصر", "المغرب", "العشاء"};

    private IslamicLine() {
    }

    static CharSequence today() {
        Calendar now = Calendar.getInstance();

        android.icu.util.IslamicCalendar h = new android.icu.util.IslamicCalendar();
        h.setCalculationType(android.icu.util.IslamicCalendar.CalculationType.ISLAMIC_UMALQURA);
        h.setTimeInMillis(now.getTimeInMillis());
        int hDay = h.get(android.icu.util.Calendar.DAY_OF_MONTH);
        int hMonth = h.get(android.icu.util.Calendar.MONTH); // 0-based
        int hYear = h.get(android.icu.util.Calendar.YEAR);

        double tz = TimeZone.getDefault().getOffset(now.getTimeInMillis()) / 3_600_000.0;
        double[] t = times(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH),
                LATITUDE, LONGITUDE, tz, hMonth == 8);

        double nowHours = now.get(Calendar.HOUR_OF_DAY) + now.get(Calendar.MINUTE) / 60.0;
        int next = -1;
        for (int i = 0; i < 5; i++) {
            if (t[i] > nowHours) { next = i; break; }
        }

        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(hDay + " " + MONTHS[hMonth] + " " + hYear + " هـ  ·  " + CITY);
        sb.append('\n');
        for (int i = 0; i < 5; i++) {
            if (i > 0) sb.append("   ");
            int start = sb.length();
            sb.append(NAMES[i]).append(' ').append(format(t[i]));
            if (i == next) {
                sb.setSpan(new ForegroundColorSpan(NEXT_COLOR), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return sb;
    }

    /** 12-hour clock without AM/PM, like "4:36". */
    static String format(double hours) {
        int total = (int) Math.round(hours * 60);
        total = ((total % 1440) + 1440) % 1440;
        int hh = total / 60, mm = total % 60;
        int h12 = hh % 12 == 0 ? 12 : hh % 12;
        return h12 + ":" + (mm < 10 ? "0" : "") + mm;
    }

    /** Fajr, Dhuhr, Asr, Maghrib, Isha in local hours. The PrayTimes.org method, one refinement pass. */
    static double[] times(int y, int m, int d, double lat, double lng, double tz, boolean ramadan) {
        double jDate = julian(y, m, d) - lng / (15 * 24.0);
        double fajr = sunAngleTime(jDate, lat, FAJR_ANGLE, 5 / 24.0, true);
        double dhuhr = midDay(jDate, 12 / 24.0);
        double asr = asrTime(jDate, lat, 1, 13 / 24.0);
        double maghrib = sunAngleTime(jDate, lat, 0.833, 18 / 24.0, false);
        double adjust = tz - lng / 15.0;
        fajr += adjust;
        dhuhr += adjust;
        asr += adjust;
        maghrib += adjust;
        double isha = maghrib + (ramadan ? 2.0 : 1.5);
        return new double[]{fajr, dhuhr, asr, maghrib, isha};
    }

    private static double julian(int year, int month, int day) {
        if (month <= 2) {
            year -= 1;
            month += 12;
        }
        double a = Math.floor(year / 100.0);
        double b = 2 - a + Math.floor(a / 4);
        return Math.floor(365.25 * (year + 4716)) + Math.floor(30.6001 * (month + 1)) + day + b - 1524.5;
    }

    /** {declination in degrees, equation of time in hours} */
    private static double[] sun(double jd) {
        double dd = jd - 2451545.0;
        double g = fixAngle(357.529 + 0.98560028 * dd);
        double q = fixAngle(280.459 + 0.98564736 * dd);
        double l = fixAngle(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g));
        double e = 23.439 - 0.00000036 * dd;
        double ra = Math.toDegrees(Math.atan2(dcos(e) * dsin(l), dcos(l))) / 15.0;
        double eqt = q / 15.0 - fixHour(ra);
        double decl = Math.toDegrees(Math.asin(dsin(e) * dsin(l)));
        return new double[]{decl, eqt};
    }

    private static double midDay(double jDate, double t) {
        return fixHour(12 - sun(jDate + t)[1]);
    }

    private static double sunAngleTime(double jDate, double lat, double angle, double t, boolean beforeNoon) {
        double decl = sun(jDate + t)[0];
        double noon = midDay(jDate, t);
        double x = (-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat));
        x = Math.max(-1, Math.min(1, x));
        double span = Math.toDegrees(Math.acos(x)) / 15.0;
        return noon + (beforeNoon ? -span : span);
    }

    private static double asrTime(double jDate, double lat, double factor, double t) {
        double decl = sun(jDate + t)[0];
        double angle = -Math.toDegrees(Math.atan(1 / (factor + Math.tan(Math.toRadians(Math.abs(lat - decl))))));
        return sunAngleTime(jDate, lat, angle, t, false);
    }

    private static double dsin(double d) {
        return Math.sin(Math.toRadians(d));
    }

    private static double dcos(double d) {
        return Math.cos(Math.toRadians(d));
    }

    private static double fixAngle(double a) {
        a = a - 360.0 * Math.floor(a / 360.0);
        return a < 0 ? a + 360 : a;
    }

    private static double fixHour(double a) {
        a = a - 24.0 * Math.floor(a / 24.0);
        return a < 0 ? a + 24 : a;
    }
}
