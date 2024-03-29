package src.utils;

public interface ANSIColors {
    String ANSI_RESET = "\u001B[0m";
    String ANSI_RED = "\u001B[31m";
    String ANSI_GREEN = "\u001B[32m";
    String ANSI_YELLOW = "\u001B[33m";
    String ANSI_CYAN = "\u001B[36m";
    String ANSI_BLUE = "\u001B[34m";

    static void coloredPrint(String color, String message) {
        System.out.println(color + message + ANSI_RESET);
    }
}
