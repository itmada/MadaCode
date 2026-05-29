package madacode.skill;

public final class SkillBodyRenderer {

    private SkillBodyRenderer() {}

    public static String render(Skill skill, String task) {
        StringBuilder sb = new StringBuilder();
        if (skill.baseDir() != null) {
            sb.append("Base directory: ").append(skill.baseDir()).append("\n\n");
        }
        sb.append(skill.body());
        if (task != null && !task.isBlank()) {
            sb.append("\n\n## Task\n").append(task);
        }
        return sb.toString();
    }
}
