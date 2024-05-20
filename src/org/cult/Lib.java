package org.cult;

public class Lib {

    public static void assertEquals(Object expected, Object toTest) {
        if (!expected.equals(toTest)) {
            throw new AssertionError(STR."Expected \{expected} but got \{toTest}");
        } else {
            System.out.println(".");
        }
    }

}


