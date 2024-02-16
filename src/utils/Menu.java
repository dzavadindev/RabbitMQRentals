package src.utils;

import java.util.Scanner;

public abstract class Menu {
    public static int printMenu(String... options) {
        Scanner sc = new Scanner(System.in);
        for (int i = 0; i < options.length; i++) {
            System.out.println((i + 1) + ".) " + options[i]);
        }
        System.out.println((options.length + 1) + ".) Quit");
        System.out.println("Select an option from the above: ");
        int input = sc.nextInt();
        return input != options.length + 1 ? input : 0;
    }
}
