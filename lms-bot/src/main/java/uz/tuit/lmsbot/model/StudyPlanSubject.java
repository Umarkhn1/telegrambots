package uz.tuit.lmsbot.model;

import lombok.Data;

@Data
public class StudyPlanSubject {
    private String name;
    private int credits;
    private Integer grade; // null = нет оценки
    private int semester;

    /** Баллы в GPA. Двойка — незачёт: в сумму идёт нулём, но её кредиты остаются в знаменателе. */
    public static int gradePoints(int grade) {
        return grade >= 3 ? grade : 0;
    }
}