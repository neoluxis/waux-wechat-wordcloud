import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;

String PLUGIN_TZ = "Asia/Shanghai";
int MAX_RECORD_FILE_LINES = 6000;
long MAX_RECORD_FILE_BYTES = 1024L * 1024L * 2L;
long MEDIA_TEXT_DELAY_MS = 4500L;
Object WRITE_LOCK = new Object();
List<Runnable> WRITE_QUEUE = new LinkedList<Runnable>();
Thread WRITE_WORKER = null;
boolean WRITE_WORKER_RUNNING = false;

String CMD_HINT =
    "微信词云\n" +
    "命令示例：\n" +
    ",wc\n" +
    ",wc -t -3d\n" +
    ",wc -t d0326-0327 -f 测试 -f 哈哈\n" +
    ",wc -t t1200-1300\n\n" +
    "支持前缀：,wc  ，wc  /wc\n" +
    "支持配置入口：,wcm  ，wcm  /wcm";

Set<String> STOP_WORDS = new HashSet<String>(Arrays.asList(
    "的", "了", "是", "我", "你", "他", "她", "它", "们", "啊", "呀", "哦", "嗯", "哈",
    "哈哈", "嘿", "吗", "呢", "吧", "啦", "喔", "欸", "额", "就是", "这个", "那个", "然后",
    "因为", "所以", "如果", "但是", "而且", "已经", "还是", "我们", "你们", "他们", "自己",
    "一下", "一个", "没有", "什么", "怎么", "为什么", "真的", "可以", "不是", "不会", "不是",
    "and", "the", "for", "you", "that", "this", "with", "are", "was", "have", "just",
    "from", "your", "they", "what", "when", "where", "will", "would", "then", "than"
));

class ParsedCommand {
    String action;
    long startTime;
    long endTime;
    List<String> filters;
    String error;
}

class MsgRecord {
    long time;
    boolean self;
    String sender;
    String content;
}

class WordStat {
    String word;
    int count;
}

class DrawWord {
    String word;
    int count;
    float textSize;
    int color;
}

void onLoad() {
    ensureRecordDir();
    ensureWriteWorker();
    log("微信词云分析已加载");
}

void onUnLoad() {
    stopWriteWorker();
    log("微信词云分析已卸载");
}

void onUnload() {
    onUnLoad();
}

boolean onClickSendBtn(String text) {
    try {
        ParsedCommand command = parseCommand(text);
        if (command == null) {
            return false;
        }
        String talker = getTargetTalker();
        handleParsedCommand(talker, command, true, safeLoginWxid());
        return true;
    } catch (Exception e) {
        log("onClickSendBtn error: " + e.toString());
        toast("词云命令处理失败");
        return true;
    }
}

void onHandleMsg(Object msgInfoBean) {
    try {
        Object msg = msgInfoBean;
        if (!msg.isText()) {
            return;
        }

        String talker = safeString(msg.getTalker());
        String content = safeString(msg.getContent());
        String sender = safeString(msg.getSendTalker());
        boolean isSelf = msg.isSend();

        if (isPluginGeneratedMessage(content)) {
            return;
        }

        ParsedCommand command = parseCommand(content);
        if (command != null) {
            handleParsedCommand(talker, command, isSelf, sender);
            return;
        }

        if (!content.trim().isEmpty()) {
            enqueuePersistRecord(talker, sender, msg.getCreateTime(), isSelf, content);
        }
    } catch (Exception e) {
        log("onHandleMsg error: " + e.toString());
    }
}

void handleParsedCommand(String talker, ParsedCommand command, boolean triggerBySelf, String triggerSender) {
    if ("config".equals(command.action)) {
        sendText(talker,
            "UI 配置暂未实现。\n" +
            "当前请直接用命令参数：\n" +
            ",wc -t -1d -f 过滤词");
        return;
    }

    if (command.error != null) {
        sendText(talker, command.error + "\n\n" + CMD_HINT);
        return;
    }

    if (triggerBySelf) {
        toast("词云生成中，请稍候...");
    } else {
        String displayName = resolveDisplayName(triggerSender, talker);
        sendText(talker, displayName + " 生成了词云，请稍候...");
    }
    runAnalysisInBackground(talker, command.startTime, command.endTime, command.filters);
}

void ensureWriteWorker() {
    synchronized (WRITE_LOCK) {
        if (WRITE_WORKER != null && WRITE_WORKER.isAlive()) {
            return;
        }
        WRITE_WORKER_RUNNING = true;
        WRITE_WORKER = new Thread(new Runnable() {
            public void run() {
                while (true) {
                    Runnable task = null;
                    synchronized (WRITE_LOCK) {
                        while (WRITE_WORKER_RUNNING && WRITE_QUEUE.isEmpty()) {
                            try {
                                WRITE_LOCK.wait();
                            } catch (Exception ignore) {
                            }
                        }
                        if (!WRITE_WORKER_RUNNING && WRITE_QUEUE.isEmpty()) {
                            return;
                        }
                        task = WRITE_QUEUE.remove(0);
                    }
                    try {
                        task.run();
                    } catch (Exception e) {
                        log("write worker task error: " + e.toString());
                    }
                }
            }
        }, "wc-record-writer");
        WRITE_WORKER.start();
    }
}

void stopWriteWorker() {
    synchronized (WRITE_LOCK) {
        WRITE_WORKER_RUNNING = false;
        WRITE_LOCK.notifyAll();
    }
}

void enqueueWriteTask(Runnable task) {
    ensureWriteWorker();
    synchronized (WRITE_LOCK) {
        WRITE_QUEUE.add(task);
        WRITE_LOCK.notifyAll();
    }
}

void enqueuePersistRecord(final String talker, final String sender, final long createTime, final boolean self, final String content) {
    enqueueWriteTask(new Runnable() {
        public void run() {
            persistRecord(talker, sender, createTime, self, content);
        }
    });
}

void runAnalysisInBackground(final String talker, final long startTime, final long endTime, final List<String> filters) {
    final List<String> copiedFilters = new ArrayList<String>(filters);
    new Thread(new Runnable() {
        public void run() {
            generateWordCloudForTalker(talker, startTime, endTime, copiedFilters);
        }
    }, "wc-analysis-" + System.currentTimeMillis()).start();
}

ParsedCommand parseCommand(String raw) {
    if (raw == null) {
        return null;
    }

    String text = raw.trim();
    if (text.isEmpty()) {
        return null;
    }

    if (matchesPrefix(text, ",wcm") || matchesPrefix(text, "，wcm") || matchesPrefix(text, "/wcm")) {
        ParsedCommand command = new ParsedCommand();
        command.action = "config";
        return command;
    }

    if (!(matchesPrefix(text, ",wc") || matchesPrefix(text, "，wc") || matchesPrefix(text, "/wc"))) {
        return null;
    }

    ParsedCommand command = new ParsedCommand();
    command.action = "cloud";
    command.filters = new ArrayList<String>();

    long now = System.currentTimeMillis();
    command.startTime = now - 24L * 60L * 60L * 1000L;
    command.endTime = now;

    List<String> args = tokenizeCommand(text.substring(3).trim());
    for (int i = 0; i < args.size(); i++) {
        String token = args.get(i);
        if ("-t".equals(token)) {
            if (i + 1 >= args.size()) {
                command.error = "缺少 -t 对应的时间参数";
                return command;
            }
            long[] range = parseTimeRange(args.get(++i), now);
            if (range == null) {
                command.error = "时间参数格式错误：" + args.get(i);
                return command;
            }
            command.startTime = range[0];
            command.endTime = range[1];
        } else if ("-f".equals(token)) {
            if (i + 1 >= args.size()) {
                command.error = "缺少 -f 对应的过滤词";
                return command;
            }
            String filter = args.get(++i).trim();
            if (!filter.isEmpty()) {
                command.filters.add(filter);
            }
        } else {
            command.error = "无法识别的参数：" + token;
            return command;
        }
    }

    if (command.endTime < command.startTime) {
        command.error = "时间区间无效：结束时间早于开始时间";
    }
    return command;
}

boolean matchesPrefix(String text, String prefix) {
    return text.equals(prefix) || text.startsWith(prefix + " ");
}

List<String> tokenizeCommand(String input) {
    List<String> result = new ArrayList<String>();
    if (input == null || input.isEmpty()) {
        return result;
    }
    String[] parts = input.trim().split("\\s+");
    for (int i = 0; i < parts.length; i++) {
        if (!parts[i].isEmpty()) {
            result.add(parts[i]);
        }
    }
    return result;
}

long[] parseTimeRange(String spec, long now) {
    if (spec == null || spec.trim().isEmpty()) {
        return null;
    }

    spec = spec.trim();
    if (spec.startsWith("-")) {
        long delta = parseRelativeDuration(spec);
        if (delta <= 0L) {
            return null;
        }
        return new long[] { now - delta, now };
    }

    if (spec.startsWith("t")) {
        String body = spec.substring(1);
        if (body.contains("-")) {
            String[] pair = body.split("-", 2);
            if (pair.length != 2) {
                return null;
            }
            Long start = parseTodayTime(pair[0], now);
            Long end = parseTodayTime(pair[1], now);
            if (start == null || end == null) {
                return null;
            }
            return new long[] { start.longValue(), end.longValue() };
        }
        Long start = parseTodayTime(body, now);
        if (start == null) {
            return null;
        }
        return new long[] { start.longValue(), now };
    }

    if (spec.startsWith("d")) {
        String body = spec.substring(1);
        if (body.contains("-")) {
            String[] pair = body.split("-", 2);
            if (pair.length != 2 || pair[0].length() != pair[1].length()) {
                return null;
            }
            Long start = parseDateTimeToken(pair[0], now);
            Long end = parseDateTimeToken(pair[1], now);
            if (start == null || end == null) {
                return null;
            }
            return new long[] { start.longValue(), end.longValue() };
        }
        Long start = parseDateTimeToken(body, now);
        if (start == null) {
            return null;
        }
        return new long[] { start.longValue(), now };
    }

    return null;
}

long parseRelativeDuration(String spec) {
    String body = spec.substring(1).trim().toLowerCase(Locale.ROOT);
    if (body.isEmpty()) {
        return -1L;
    }

    long unit;
    String numberPart;
    if (body.endsWith("min")) {
        unit = 60L * 1000L;
        numberPart = body.substring(0, body.length() - 3);
    } else {
        char tail = body.charAt(body.length() - 1);
        numberPart = body.substring(0, body.length() - 1);
        if (tail == 'm') {
            unit = 30L * 24L * 60L * 60L * 1000L;
        } else if (tail == 'w') {
            unit = 7L * 24L * 60L * 60L * 1000L;
        } else if (tail == 'd') {
            unit = 24L * 60L * 60L * 1000L;
        } else if (tail == 'h') {
            unit = 60L * 60L * 1000L;
        } else {
            return -1L;
        }
    }

    try {
        long value = Long.parseLong(numberPart);
        if (value <= 0L) {
            return -1L;
        }
        return value * unit;
    } catch (Exception e) {
        return -1L;
    }
}

Long parseTodayTime(String hhmm, long now) {
    if (hhmm == null || hhmm.length() != 4) {
        return null;
    }
    try {
        int hour = Integer.parseInt(hhmm.substring(0, 2));
        int minute = Integer.parseInt(hhmm.substring(2, 4));
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            return null;
        }

        Calendar calendar = newCalendar(now);
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return Long.valueOf(calendar.getTimeInMillis());
    } catch (Exception e) {
        return null;
    }
}

Long parseDateTimeToken(String token, long now) {
    if (token == null) {
        return null;
    }
    try {
        Calendar calendar = newCalendar(now);
        if (token.length() == 4) {
            int month = Integer.parseInt(token.substring(0, 2));
            int day = Integer.parseInt(token.substring(2, 4));
            calendar.set(Calendar.MONTH, month - 1);
            calendar.set(Calendar.DAY_OF_MONTH, day);
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            return Long.valueOf(calendar.getTimeInMillis());
        }
        if (token.length() == 8) {
            int year = Integer.parseInt(token.substring(0, 4));
            int month = Integer.parseInt(token.substring(4, 6));
            int day = Integer.parseInt(token.substring(6, 8));
            calendar.set(Calendar.YEAR, year);
            calendar.set(Calendar.MONTH, month - 1);
            calendar.set(Calendar.DAY_OF_MONTH, day);
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            return Long.valueOf(calendar.getTimeInMillis());
        }
        if (token.length() == 12) {
            int year = Integer.parseInt(token.substring(0, 4));
            int month = Integer.parseInt(token.substring(4, 6));
            int day = Integer.parseInt(token.substring(6, 8));
            int hour = Integer.parseInt(token.substring(8, 10));
            int minute = Integer.parseInt(token.substring(10, 12));
            calendar.set(Calendar.YEAR, year);
            calendar.set(Calendar.MONTH, month - 1);
            calendar.set(Calendar.DAY_OF_MONTH, day);
            calendar.set(Calendar.HOUR_OF_DAY, hour);
            calendar.set(Calendar.MINUTE, minute);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            return Long.valueOf(calendar.getTimeInMillis());
        }
    } catch (Exception e) {
        log("parseDateTimeToken error: " + e.toString());
    }
    return null;
}

Calendar newCalendar(long millis) {
    Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone(PLUGIN_TZ));
    calendar.setLenient(false);
    calendar.setTimeInMillis(millis);
    return calendar;
}

void persistRecord(String talker, String sender, long createTime, boolean self, String content) {
    try {
        if (talker == null || talker.trim().isEmpty()) {
            return;
        }
        if (content == null) {
            return;
        }
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (parseCommand(trimmed) != null) {
            return;
        }

        File file = getRecordFile(talker);
        BufferedWriter writer = new BufferedWriter(new FileWriter(file, true));
        try {
            writer.write(String.valueOf(createTime));
            writer.write("\t");
            writer.write(self ? "1" : "0");
            writer.write("\t");
            writer.write(Base64.encodeToString(safeString(sender).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
            writer.write("\t");
            writer.write(Base64.encodeToString(trimmed.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
            writer.newLine();
        } finally {
            writer.close();
        }
        trimRecordFileIfNeeded(file);
    } catch (Exception e) {
        log("persistRecord error: " + e.toString());
    }
}

void trimRecordFileIfNeeded(File file) {
    try {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.length() <= MAX_RECORD_FILE_BYTES) {
            return;
        }

        List<String> lines = new ArrayList<String>();
        BufferedReader reader = new BufferedReader(new FileReader(file));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } finally {
            reader.close();
        }

        int start = lines.size() > MAX_RECORD_FILE_LINES ? (lines.size() - MAX_RECORD_FILE_LINES) : 0;
        BufferedWriter writer = new BufferedWriter(new FileWriter(file, false));
        try {
            for (int i = start; i < lines.size(); i++) {
                writer.write(lines.get(i));
                writer.newLine();
            }
        } finally {
            writer.close();
        }
    } catch (Exception e) {
        log("trimRecordFileIfNeeded error: " + e.toString());
    }
}

File ensureRecordDir() {
    File dir = new File(cacheDir, "records");
    if (!dir.exists()) {
        dir.mkdirs();
    }
    return dir;
}

File getRecordFile(String talker) {
    return new File(ensureRecordDir(), md5Hex(talker) + ".log");
}

String md5Hex(String input) {
    try {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            if (value < 16) {
                sb.append("0");
            }
            sb.append(Integer.toHexString(value));
        }
        return sb.toString();
    } catch (Exception e) {
        return String.valueOf(input.hashCode());
    }
}

void generateWordCloudForTalker(String talker, long startTime, long endTime, List<String> filters) {
    try {
        List<MsgRecord> records = loadRecords(talker, startTime, endTime, filters);
        if (records.isEmpty()) {
            sendText(talker,
                "没有可分析的文本消息。\n" +
                "当前版本只统计插件加载后缓存到本地的消息。\n" +
                "时间范围：" + formatTime(startTime) + " ~ " + formatTime(endTime));
            return;
        }

        Map<String, Integer> wordCountMap = buildWordCounts(records, filters);
        if (wordCountMap.isEmpty()) {
            sendText(talker,
                "消息已命中，但提取不到有效词语。\n" +
                "可尝试缩小过滤条件，或使用更长的时间范围。");
            return;
        }

        List<WordStat> topWords = topWordStats(wordCountMap, 50);
        File output = renderWordCloudImage(talker, topWords, records.size(), startTime, endTime);
        sendWordCloudOutput(talker, output);
        waitForMediaLikelyUploaded(output);
        sendText(talker, buildSummaryText(talker, topWords, records, filters, startTime, endTime));
    } catch (Exception e) {
        log("generateWordCloudForTalker error: " + e.toString());
        sendText(talker, "生成词云失败，请查看日志");
    }
}

void sendWordCloudOutput(String talker, File output) {
    if (output == null || !output.exists()) {
        throw new RuntimeException("word cloud output file missing");
    }

    log("word cloud output path: " + output.getAbsolutePath() + ", size=" + output.length());
    try {
        sendImage(talker, output.getAbsolutePath());
        log("sendImage png success");
        return;
    } catch (Exception imageError) {
        log("sendImage png failed: " + imageError.toString());
    }

    try {
        sendImage(talker, output.getAbsolutePath(), pluginId);
        log("sendImage png with appId success");
        return;
    } catch (Exception imageError) {
        log("sendImage png with appId failed: " + imageError.toString());
    }

    File jpgFile = null;
    try {
        jpgFile = convertPngToJpg(output);
        log("jpg output path: " + jpgFile.getAbsolutePath() + ", size=" + jpgFile.length());
    } catch (Exception jpgError) {
        log("convert jpg failed: " + jpgError.toString());
    }

    if (jpgFile != null && jpgFile.exists()) {
        try {
            sendImage(talker, jpgFile.getAbsolutePath());
            log("sendImage jpg success");
            return;
        } catch (Exception imageError) {
            log("sendImage jpg failed: " + imageError.toString());
        }

        try {
            sendImage(talker, jpgFile.getAbsolutePath(), pluginId);
            log("sendImage jpg with appId success");
            return;
        } catch (Exception imageError) {
            log("sendImage jpg with appId failed: " + imageError.toString());
        }
    }

    try {
        shareFile(talker, output.getName(), output.getAbsolutePath(), "");
        log("shareFile fallback success");
        return;
    } catch (Exception shareError) {
        log("shareFile failed: " + shareError.toString());
        throw new RuntimeException("send image and share file both failed: " + shareError.toString());
    }
}

void waitForMediaLikelyUploaded(File output) {
    try {
        Thread.sleep(MEDIA_TEXT_DELAY_MS);
    } catch (Exception ignore) {
    }
}

File convertPngToJpg(File pngFile) throws Exception {
    Bitmap source = android.graphics.BitmapFactory.decodeFile(pngFile.getAbsolutePath());
    if (source == null) {
        throw new RuntimeException("decode png failed");
    }

    Bitmap jpgBitmap = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.RGB_565);
    Canvas canvas = new Canvas(jpgBitmap);
    canvas.drawColor(Color.WHITE);
    canvas.drawBitmap(source, 0f, 0f, null);

    File jpgFile = new File(cacheDir, "wordcloud_" + System.currentTimeMillis() + ".jpg");
    FileOutputStream fos = new FileOutputStream(jpgFile);
    try {
        jpgBitmap.compress(Bitmap.CompressFormat.JPEG, 92, fos);
        fos.flush();
    } finally {
        fos.close();
    }
    return jpgFile;
}

List<MsgRecord> loadRecords(String talker, long startTime, long endTime, List<String> filters) {
    List<MsgRecord> result = new ArrayList<MsgRecord>();
    File file = getRecordFile(talker);
    if (!file.exists()) {
        return result;
    }

    List<String> normalizedFilters = new ArrayList<String>();
    for (int i = 0; i < filters.size(); i++) {
        normalizedFilters.add(filters.get(i).toLowerCase(Locale.ROOT));
    }

    BufferedReader reader = null;
    try {
        reader = new BufferedReader(new FileReader(file));
        String line;
        while ((line = reader.readLine()) != null) {
            String[] parts = line.split("\t");
            if (parts.length < 3) {
                continue;
            }

            long time = Long.parseLong(parts[0]);
            if (time < startTime || time > endTime) {
                continue;
            }

            String sender = "";
            String contentBase64;
            if (parts.length >= 4) {
                sender = new String(Base64.decode(parts[2], Base64.DEFAULT), StandardCharsets.UTF_8);
                contentBase64 = parts[3];
            } else {
                contentBase64 = parts[2];
            }

            String content = new String(Base64.decode(contentBase64, Base64.DEFAULT), StandardCharsets.UTF_8);
            if (!matchesFilters(content, normalizedFilters)) {
                continue;
            }

            MsgRecord record = new MsgRecord();
            record.time = time;
            record.self = "1".equals(parts[1]);
            record.sender = sender;
            record.content = content;
            result.add(record);
        }
    } catch (Exception e) {
        log("loadRecords error: " + e.toString());
    } finally {
        try {
            if (reader != null) {
                reader.close();
            }
        } catch (Exception ignore) {
        }
    }
    return result;
}

boolean matchesFilters(String content, List<String> filters) {
    if (filters == null || filters.isEmpty()) {
        return true;
    }
    String lower = content.toLowerCase(Locale.ROOT);
    for (int i = 0; i < filters.size(); i++) {
        if (lower.contains(filters.get(i))) {
            return false;
        }
    }
    return true;
}

Map<String, Integer> buildWordCounts(List<MsgRecord> records, List<String> filters) {
    Map<String, Integer> countMap = new HashMap<String, Integer>();
    for (int i = 0; i < records.size(); i++) {
        List<String> words = tokenizeWords(records.get(i).content);
        for (int j = 0; j < words.size(); j++) {
            String word = words.get(j);
            if (isFilteredWord(word, filters)) {
                continue;
            }
            Integer old = countMap.get(word);
            countMap.put(word, old == null ? 1 : old.intValue() + 1);
        }
    }
    return countMap;
}

boolean isFilteredWord(String word, List<String> filters) {
    if (word == null || word.isEmpty()) {
        return true;
    }
    if (STOP_WORDS.contains(word)) {
        return true;
    }
    for (int i = 0; i < filters.size(); i++) {
        String filter = filters.get(i).toLowerCase(Locale.ROOT);
        if (word.toLowerCase(Locale.ROOT).contains(filter)) {
            return true;
        }
    }
    if (word.length() == 1 && isAscii(word.charAt(0))) {
        return true;
    }
    return false;
}

List<String> tokenizeWords(String content) {
    List<String> result = new ArrayList<String>();
    if (content == null) {
        return result;
    }

    String normalized = content
        .replaceAll("https?://\\S+", " ")
        .replaceAll("\\[[^\\]]+\\]", " ")
        .replaceAll("[\\p{Punct}，。！？；：、“”‘’（）【】《》…·~`@#$%^&*_+=|\\\\/<>-]", " ")
        .replace('\n', ' ')
        .replace('\r', ' ');

    StringBuilder ascii = new StringBuilder();
    StringBuilder cjk = new StringBuilder();

    for (int i = 0; i < normalized.length(); i++) {
        char ch = normalized.charAt(i);
        if (isAsciiWordChar(ch)) {
            if (cjk.length() > 0) {
                flushCjkBuffer(cjk, result);
            }
            ascii.append(Character.toLowerCase(ch));
        } else {
            if (ascii.length() > 0) {
                flushAsciiBuffer(ascii, result);
            }
            if (isCjk(ch)) {
                cjk.append(ch);
            } else {
                if (cjk.length() > 0) {
                    flushCjkBuffer(cjk, result);
                }
            }
        }
    }
    if (ascii.length() > 0) {
        flushAsciiBuffer(ascii, result);
    }
    if (cjk.length() > 0) {
        flushCjkBuffer(cjk, result);
    }
    return result;
}

void flushAsciiBuffer(StringBuilder ascii, List<String> result) {
    String word = ascii.toString().trim();
    ascii.setLength(0);
    if (word.length() >= 2 && !STOP_WORDS.contains(word)) {
        result.add(word);
    }
}

void flushCjkBuffer(StringBuilder cjk, List<String> result) {
    String text = cjk.toString().trim();
    cjk.setLength(0);
    if (text.isEmpty()) {
        return;
    }

    if (text.length() <= 4) {
        if (!STOP_WORDS.contains(text)) {
            result.add(text);
        }
        return;
    }

    for (int i = 0; i < text.length() - 1; i++) {
        String biGram = text.substring(i, i + 2);
        if (!STOP_WORDS.contains(biGram)) {
            result.add(biGram);
        }
    }
}

boolean isAsciiWordChar(char ch) {
    return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9');
}

boolean isAscii(char ch) {
    return ch >= 0 && ch <= 127;
}

boolean isCjk(char ch) {
    Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
    return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
        || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
        || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
        || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION;
}

List<WordStat> topWordStats(Map<String, Integer> wordCountMap, int limit) {
    List<WordStat> result = new ArrayList<WordStat>();
    for (Map.Entry<String, Integer> entry : wordCountMap.entrySet()) {
        WordStat stat = new WordStat();
        stat.word = entry.getKey();
        stat.count = entry.getValue().intValue();
        result.add(stat);
    }

    Collections.sort(result, new Comparator<WordStat>() {
        public int compare(WordStat a, WordStat b) {
            if (b.count != a.count) {
                return b.count - a.count;
            }
            return a.word.compareTo(b.word);
        }
    });

    if (result.size() > limit) {
        return new ArrayList<WordStat>(result.subList(0, limit));
    }
    return result;
}

File renderWordCloudImage(String talker, List<WordStat> topWords, int messageCount, long startTime, long endTime) throws Exception {
    int width = 1080;
    int height = 1080;
    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    canvas.drawColor(Color.WHITE);

    List<RectF> placed = new ArrayList<RectF>();
    Random random = new Random((talker + startTime + ":" + endTime).hashCode());
    int maxCount = topWords.get(0).count;
    int[] palette = new int[] {
        Color.parseColor("#48C774"),
        Color.parseColor("#2E5FA7"),
        Color.parseColor("#CEDC39"),
        Color.parseColor("#1E8FAF"),
        Color.parseColor("#4B4B9A"),
        Color.parseColor("#BFD730")
    };

    for (int i = 0; i < topWords.size(); i++) {
        WordStat stat = topWords.get(i);
        DrawWord drawWord = new DrawWord();
        drawWord.word = stat.word;
        drawWord.count = stat.count;
        drawWord.textSize = 18f + (stat.count * 1f / maxCount) * 110f;
        drawWord.color = palette[i % palette.length];
        placeWord(canvas, drawWord, placed, random, width, height);
    }

    File output = new File(cacheDir, "wordcloud_" + System.currentTimeMillis() + ".png");
    FileOutputStream fos = new FileOutputStream(output);
    try {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
        fos.flush();
    } finally {
        fos.close();
    }
    return output;
}

void placeWord(Canvas canvas, DrawWord drawWord, List<RectF> placed, Random random, int width, int height) {
    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    paint.setColor(drawWord.color);
    paint.setTextSize(drawWord.textSize);
    paint.setTypeface(drawWord.count > 3 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);

    Rect bounds = new Rect();
    paint.getTextBounds(drawWord.word, 0, drawWord.word.length(), bounds);
    float wordWidth = bounds.width();
    float wordHeight = bounds.height();

    float centerX = width / 2f;
    float centerY = height / 2f;

    for (int attempt = 0; attempt < 500; attempt++) {
        double angle = attempt * 0.48;
        double radius = 8.0 + attempt * 2.65;
        float x = (float) (centerX + Math.cos(angle) * radius + random.nextInt(18) - 9);
        float y = (float) (centerY + Math.sin(angle) * radius + random.nextInt(18) - 9);

        RectF rect = new RectF(
            x - 10f,
            y - wordHeight - 10f,
            x + wordWidth + 10f,
            y + 10f
        );

        if (rect.left < 18f || rect.top < 18f || rect.right > width - 18f || rect.bottom > height - 18f) {
            continue;
        }
        if (intersectsAny(rect, placed)) {
            continue;
        }
        placed.add(rect);
        canvas.drawText(drawWord.word, x, y, paint);
        return;
    }
}

boolean intersectsAny(RectF rect, List<RectF> placed) {
    for (int i = 0; i < placed.size(); i++) {
        if (RectF.intersects(rect, placed.get(i))) {
            return true;
        }
    }
    return false;
}

String buildSummaryText(String talker, List<WordStat> topWords, List<MsgRecord> records, List<String> filters, long startTime, long endTime) {
    Map<String, Integer> userCounts = buildUserCounts(talker, records);
    List<Map.Entry<String, Integer>> topUsers = topUserEntries(userCounts, 4);
    StringBuilder sb = new StringBuilder();
    sb.append("☁️ ").append(formatDay(endTime)).append(" 热门话题 #WordCloud\n");
    sb.append("⏰ 截至今天 ").append(formatClock(endTime)).append("\n");
    sb.append("🗣️ ").append(talker.endsWith("@chatroom") ? "本群 " : "本聊天 ").append(userCounts.size()).append(" 位朋友共产生 ").append(records.size()).append(" 条发言\n");
    sb.append("🔍 看下有没有你感兴趣的关键词？\n\n");
    sb.append("活跃用户排行榜：\n\n");
    if (topUsers.isEmpty()) {
        sb.append("    暂无足够的发言者数据\n");
    } else {
        for (int i = 0; i < topUsers.size(); i++) {
            String medal = i == 0 ? "🥇" : (i == 1 ? "🥈" : (i == 2 ? "🥉" : "🎖"));
            sb.append("    ").append(medal).append(topUsers.get(i).getKey()).append(" 贡献: ").append(topUsers.get(i).getValue()).append("\n");
        }
    }
    if (filters != null && !filters.isEmpty()) {
        sb.append("\n已过滤：");
        for (int i = 0; i < filters.size(); i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(filters.get(i));
        }
        sb.append("\n");
    }
    sb.append("\n🎉感谢这些朋友今天的分享!🎉");
    return sb.toString();
}

Map<String, Integer> buildUserCounts(String talker, List<MsgRecord> records) {
    Map<String, Integer> counts = new HashMap<String, Integer>();
    for (int i = 0; i < records.size(); i++) {
        MsgRecord record = records.get(i);
        String sender = record.sender;
        if (sender == null || sender.trim().isEmpty()) {
            sender = record.self ? safeLoginWxid() : "未知用户";
        }
        String name = resolveDisplayName(sender, talker);
        Integer old = counts.get(name);
        counts.put(name, old == null ? 1 : old.intValue() + 1);
    }
    return counts;
}

List<Map.Entry<String, Integer>> topUserEntries(Map<String, Integer> userCounts, int limit) {
    List<Map.Entry<String, Integer>> entries = new ArrayList<Map.Entry<String, Integer>>(userCounts.entrySet());
    Collections.sort(entries, new Comparator<Map.Entry<String, Integer>>() {
        public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
            int diff = b.getValue().intValue() - a.getValue().intValue();
            if (diff != 0) {
                return diff;
            }
            return a.getKey().compareTo(b.getKey());
        }
    });
    if (entries.size() > limit) {
        return new ArrayList<Map.Entry<String, Integer>>(entries.subList(0, limit));
    }
    return entries;
}

String resolveDisplayName(String wxid, String talker) {
    String loginWxid = safeLoginWxid();
    boolean isSelfUser = wxid != null && wxid.equals(loginWxid);
    if (isSelfUser) {
        String selfName = resolveSelfDisplayName(talker, loginWxid);
        if (!selfName.isEmpty()) {
            return selfName;
        }
    }

    try {
        String name = "";
        if (talker != null && talker.endsWith("@chatroom")) {
            name = getFriendName(wxid, talker);
        }
        if (name == null || name.trim().isEmpty() || "WeChat User".equals(name)) {
            name = getFriendName(wxid);
        }
        if (name != null && !name.trim().isEmpty() && !"WeChat User".equals(name)) {
            return name.trim();
        }
    } catch (Exception ignore) {
    }

    if (isSelfUser) {
        String selfName = resolveSelfDisplayName(talker, loginWxid);
        if (!selfName.isEmpty()) {
            return selfName;
        }
    }
    return (wxid == null || wxid.trim().isEmpty()) ? "未知用户" : wxid;
}

String resolveSelfDisplayName(String talker, String loginWxid) {
    String alias = safeLoginAlias();
    if (!alias.isEmpty() && !"WeChat User".equals(alias)) {
        return alias;
    }

    try {
        if (talker != null && talker.endsWith("@chatroom")) {
            String roomName = getFriendName(loginWxid, talker);
            if (roomName != null && !roomName.trim().isEmpty() && !"WeChat User".equals(roomName)) {
                return roomName.trim();
            }
        }
    } catch (Exception ignore) {
    }

    try {
        String friendName = getFriendName(loginWxid);
        if (friendName != null && !friendName.trim().isEmpty() && !"WeChat User".equals(friendName)) {
            return friendName.trim();
        }
    } catch (Exception ignore) {
    }

    return alias;
}

String safeLoginWxid() {
    try {
        return safeString(getLoginWxid());
    } catch (Exception e) {
        return "self";
    }
}

String safeLoginAlias() {
    try {
        return safeString(getLoginAlias());
    } catch (Exception e) {
        return "";
    }
}

String formatDay(long time) {
    SimpleDateFormat sdf = new SimpleDateFormat("MM-dd", Locale.getDefault());
    sdf.setTimeZone(TimeZone.getTimeZone(PLUGIN_TZ));
    return sdf.format(new Date(time));
}

String formatClock(long time) {
    SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
    sdf.setTimeZone(TimeZone.getTimeZone(PLUGIN_TZ));
    return sdf.format(new Date(time));
}

String formatTime(long time) {
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
    sdf.setTimeZone(TimeZone.getTimeZone(PLUGIN_TZ));
    return sdf.format(new Date(time));
}

boolean isPluginGeneratedMessage(String content) {
    if (content == null) {
        return false;
    }
    String text = content.trim();
    if (text.isEmpty()) {
        return false;
    }
    return text.startsWith("☁️ ")
        || text.startsWith("微信词云")
        || text.startsWith("词云生成中，请稍候")
        || text.startsWith("UI 配置暂未实现")
        || text.startsWith("没有可分析的文本消息")
        || text.startsWith("消息已命中，但提取不到有效词语")
        || text.startsWith("生成词云失败");
}

String safeString(String value) {
    return value == null ? "" : value;
}
