package uz.tuit.lmsbot.bot;

public class UserSettings {
    // Stored to keep language across restarts (session/cookies are separate)
    public String lang = null; // ru, uz_lat, uz_cyr

    public static UserSettings defaults() {
        return new UserSettings();
    }
}

