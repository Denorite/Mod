package com.denorite;

public class DenoriteBanner {
    // ANSI color codes for console
    private static final String RESET = "\u001B[0m";
    private static final String BLUE = "\u001B[38;5;117m";      // Light blue for glass
    private static final String GOLD = "\u001B[38;5;172m";      // Gold for cork
    private static final String PINK = "\u001B[38;5;213m";      // Pink for potion
    private static final String PINK_DIM = "\u001B[38;5;211m";  // Dimmer pink for shadows
    private static final String WHITE = "\u001B[38;5;255m";     // Bright white
    private static final String DARK_GRAY = "\u001B[38;5;240m"; // Dark gray
    private static final String GRAY = "\u001B[38;5;245m";      // Gray

    private static final String[] POTION = {
            "             .......                ",
            "            :ooooool;;;.            ",
            "        .,,,odddddddlll:'''         ",
            "        cXXX0OOOOOOOOOO0000.        ",
            "        :KKKxooooooolllx000.        ",
            "        ;000l;;;;;;;,,,o000.        ",
            "        .'''oxxx::::ccc;'''         ",
            "            d000ccccooo'            ",
            "            d000;;;:ooo'            ",
            "            d000;;;:ooo'            ",
            "        ;000l:::;;;;;;;:ccc         ",
            "        cXXXl;;;;;;;;;;cooo.        ",
            "    .XXXOdddO000dddo;;;:ccclooo     ",
            " .. ,XXXOdddOOOOdddo:::cllloooo     ",
            " XXXKdddk000occcOOOOOOO0XXXxdddooo: ",
            " XXXKdddk000dlllOOOOOOOKXXXOxxxooo: ",
            " XXXKdddk000OOOOXXXXXXXXXXXXXXXooo: ",
            " XXX0dddk0000000XXXXNNNXXXXXXXXooo: ",
            " 000OdddxOOOKXXXXXXXWWWNXXXXXXXooo: ",
            " xxxxxxxO000XXXXNNNNWWWNNNNK000ooo: ",
            " ooodOOO0XXXXXXXWWWWWWWWWWW0OOOooo: ",
            " ...'dddk000XNNNWWWWWWWX000xddd.... ",
            "    .oooxOOOXWWWWWWWWWWKOOOdooo     ",
            "     ...,ddddxxxxxxxxxxdddd....     ",
            "        'oooooooooooooooooo.        "
    };

    public static void printBanner() {

        // Print the potion
        for (int i = 0; i < POTION.length; i++) {
            StringBuilder line = new StringBuilder();
            for (char c : POTION[i].toCharArray()) {
                switch (c) {
                    case 'o':
                        line.append(i < 4 ? GOLD + c : BLUE + c);
                        break;
                    case 'X':
                    case 'K':
                    case 'N':
                    case 'W':
                        line.append(PINK + c);
                        break;
                    case 'd':
                    case 'O':
                        line.append(PINK_DIM + c);
                        break;
                    case '.':
                    case '\'':
                        line.append(WHITE + c);
                        break;
                    case 'c':
                    case ':':
                    case ';':
                        line.append(BLUE + c);
                        break;
                    default:
                        line.append(c);
                }
            }
            System.out.println(CENTER + line.toString() + RESET);
        }
    }

    // Center text based on console width
    private static final String CENTER = String.format("%" + 21 + "s", "");
}
