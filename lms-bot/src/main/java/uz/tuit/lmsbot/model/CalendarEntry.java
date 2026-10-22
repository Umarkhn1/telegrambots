package uz.tuit.lmsbot.model;

import java.util.List;
import java.util.ArrayList;

public class CalendarEntry {
    private int    number;
    private String topic;
    private String date;
    private List<FileAttachment> files = new ArrayList<>();

    public int    getNumber() { return number; }
    public String getTopic()  { return topic; }
    public String getDate()   { return date; }
    public List<FileAttachment> getFiles() { return files; }

    public void setNumber(int number)            { this.number = number; }
    public void setTopic(String topic)           { this.topic = topic; }
    public void setDate(String date)             { this.date = date; }
    public void setFiles(List<FileAttachment> f) { this.files = f; }

    public static class FileAttachment {
        private final String name;
        private final String url;
        private final String type; // pdf, ppt, doc, video, url, file

        public FileAttachment(String name, String url, String type) {
            this.name = name;
            this.url  = url;
            this.type = type;
        }
        public String getName() { return name; }
        public String getUrl()  { return url; }
        public String getType() { return type; }
    }
}