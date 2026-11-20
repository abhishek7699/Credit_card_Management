package com.creditcard.mastercard.developers;

/** Splits the single "name on card" a guest types into Mastercard's firstName / lastName. */
final class CardholderName {

    private static final String NO_FIRST = "#NOFIRSTNAME";
    private static final String NO_LAST = "#NOLASTNAME";

    private CardholderName() {}

    /** "Jane Michelle Candy-Smith" -> [Jane, Candy-Smith]. One word -> last name only. Blank -> both placeholders. */
    static String[] split(String fullName) {
        String trimmed = fullName == null ? "" : fullName.trim();
        if (trimmed.isEmpty()) {
            return new String[] {NO_FIRST, NO_LAST};
        }
        String[] parts = trimmed.split("\\s+");
        if (parts.length == 1) {
            return new String[] {NO_FIRST, parts[0]};
        }
        return new String[] {parts[0], parts[parts.length - 1]};
    }
}
