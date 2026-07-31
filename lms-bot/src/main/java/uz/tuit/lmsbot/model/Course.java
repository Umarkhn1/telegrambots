package uz.tuit.lmsbot.model;

import lombok.Data;

@Data
public class Course {
    private int id;
    private String subject;
    private String teachers;
    private String streams;
    private int attendance;
    private boolean failed;
    private int semesterId;
    private String downloadQuestionsUrl;

    public String getFormattedTeachers() {
        if (teachers == null || teachers.isEmpty()) return "—";
        String[] teacherList = teachers.split("###");
        String[] streamList = (streams != null && !streams.isEmpty()) ? streams.split("###") : new String[0];
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < teacherList.length; i++) {
            String stream = i < streamList.length ? streamList[i] : "";
            if (!stream.isEmpty()) {
                sb.append(stream).append(" - ");
            }
            sb.append(teacherList[i].trim());
            if (i < teacherList.length - 1) sb.append("\n");
        }
        return sb.toString();
    }
}
