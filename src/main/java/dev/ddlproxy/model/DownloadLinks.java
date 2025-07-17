package dev.ddlproxy.model;

import dev.ddlproxy.config.HostBlacklistContext;
import dev.ddlproxy.config.HostPriorityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;

/*
 * Outer list is host, inner list is the part(s)
 * This should probably be changed lol
 * [
 *  ["gofile.com/link1", "gofile.com/link2"],
 *  ["buzzheavier.com/link"]
 * ]
 */
public final class DownloadLinks {
    private final ArrayList<ArrayList<String>> links;
    private final String title;

    private static final Logger logger = LoggerFactory.getLogger(DownloadLinks.class);

    public DownloadLinks(
            ArrayList<ArrayList<String>> links,
            String title
    ) {
        this.links = links;
        this.title = title;
    }

    public ArrayList<ArrayList<String>> getLinksInPriority() {
        links.sort(new Comparator<>() {
            @Override
            public int compare(ArrayList<String> linkGroup1, ArrayList<String> linkGroup2) {
                return Integer.compare(getPriority(linkGroup1.getFirst()), getPriority(linkGroup2.getFirst()));
            }

            private int getPriority(String link) {
                String finalLink = link.toLowerCase();
                return HostPriorityContext.get().getMapping().entrySet().stream()
                        .filter(entry -> finalLink.contains(entry.getKey()))
                        .map(Map.Entry::getValue)
                        .findFirst()
                        .orElse(Integer.MAX_VALUE);
            }
        });

        // quick fix for blacklisting hosts that require cloudflare
        links.removeIf(innerList -> !innerList.isEmpty() && (
                HostBlacklistContext.get().getHosts().stream().anyMatch(
                        domain -> innerList.getFirst().toLowerCase().contains(domain.toLowerCase())
                )
        ));

        logger.trace("Final links list: {}", links);
        return links;
    }

    public ArrayList<ArrayList<String>> links() {
        return links;
    }

    public String title() {
        return title;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (DownloadLinks) obj;
        return Objects.equals(this.links, that.links) &&
                Objects.equals(this.title, that.title);
    }

    @Override
    public int hashCode() {
        return Objects.hash(links, title);
    }

    @Override
    public String toString() {
        return "DownloadLinks[" +
                "links=" + links + ", " +
                "title=" + title + ']';
    }

}