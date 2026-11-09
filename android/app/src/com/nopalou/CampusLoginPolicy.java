package com.nopalou;

/** Session expiry can recover automatically; a rejected password or MFA needs the user. */
final class CampusLoginPolicy {
    static boolean recover(String attention, boolean rejected, String savedOwner, String currentOwner) {
        return "login".equals(attention) && !rejected && savedOwner!=null && !savedOwner.isEmpty() && savedOwner.equals(currentOwner);
    }
}
