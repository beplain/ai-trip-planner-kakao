package com.tripplanner.place;

import java.util.*;

public final class DestinationRegions {
    private DestinationRegions() {}
    private static final String[][] ALIASES = {
        {"서울특별시","서울"},{"부산광역시","부산"},{"대구광역시","대구"},{"인천광역시","인천"},
        {"대전광역시","대전"},{"울산광역시","울산"},{"세종특별자치시","세종"},{"경기도","경기"},
        {"강원특별자치도","강원도","강원"},{"충청북도","충북"},{"충청남도","충남"},
        {"전북특별자치도","전라북도","전북"},{"전남광주통합특별시","광주광역시","광주","전라남도","전남"},
        {"경상북도","경북"},{"경상남도","경남"},{"제주특별자치도","제주도","제주"}
    };
    public static String normalize(String address) {
        if (address == null) return "";
        String text=address.trim().replaceFirst("^(대한민국|한국)\\s+", "").replaceAll("\\s+"," ");
        for (String[] names:ALIASES) for(String name:names)
            if(text.equals(name) || text.startsWith(name+" ")) return names[0]+text.substring(name.length());
        return text;
    }
    public static boolean contains(String destination,String address) {
        String target=normalize(destination), actual=normalize(address);
        return !target.isBlank() && (actual.equals(target) || actual.startsWith(target+" "));
    }
    public static List<String> providerQueries(String query) {
        if (!query.contains("전남광주통합특별시")) return List.of(query);
        return List.of(query.replace("전남광주통합특별시","광주광역시"),query.replace("전남광주통합특별시","전라남도"));
    }
}
