package madacode.governance;

public record EgressEvent(String destination, boolean blocked, String detail) {
}
