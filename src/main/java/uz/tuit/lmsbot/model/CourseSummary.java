package uz.tuit.lmsbot.model;

import lombok.Data;
import java.util.List;

@Data
public class CourseSummary {
    private String earned;
    private String maxScore;
    private String progress;
    private String grade;
    private List<Activity> activities;
}
