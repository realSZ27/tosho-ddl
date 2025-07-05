package dev.ddlproxy.model;

import java.util.ArrayList;
import java.util.Comparator;

/*
 * Outer list is host, inner list are the part(s)
 * [
 *  ["gofile.com/link1", gofile.com/link2],
 *  ["buzzheavier.com/link"]
 * ]
 */
public record DownloadLinks(
        ArrayList<ArrayList<String>> links,
        String title
) {
    public ArrayList<ArrayList<String>> getLinksInPriority() {
        links.sort(new Comparator<>() {
            @Override
            public int compare(ArrayList<String> linkGroup1, ArrayList<String> linkGroup2) {
                return Integer.compare(getPriority(linkGroup1.getFirst()), getPriority(linkGroup2.getFirst()));
            }

            private int getPriority(String link) {
                if (link.contains("gofile")) return 1;       // Highest priority
                if (link.contains("buzzheavier")) return 2;  // Medium priority
                return Integer.MAX_VALUE;                    // Lowest/default priority
            }
        });

        return links;
    }
}