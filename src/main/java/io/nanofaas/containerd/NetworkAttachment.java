package io.nanofaas.containerd;

import java.util.List;

/**
 * What a container got when it was attached to its network.
 *
 * <p>Reported back rather than discarded because a caller almost always needs it: something has to
 * route to the container, and the alternative is running {@code ip addr} inside it and parsing the
 * output, which is what this library's own tests had to do before this existed.
 *
 * @param addresses the addresses assigned, in CIDR form, in the order the network reported them
 * @param gateways the gateways for those addresses
 * @param nameservers DNS servers the container should use; empty if the network specifies none
 * @param searchDomains DNS search domains
 * @param domain the DNS domain, or {@code null} if the network specifies none
 */
public record NetworkAttachment(List<String> addresses, List<String> gateways,
                                List<String> nameservers, List<String> searchDomains,
                                String domain) {

    /** An attachment that carries nothing, for a network that reported no configuration. */
    public static final NetworkAttachment EMPTY =
            new NetworkAttachment(List.of(), List.of(), List.of(), List.of(), null);

    /** Defensively copies the lists, so an attachment cannot change under its holder. */
    public NetworkAttachment {
        addresses = List.copyOf(addresses);
        gateways = List.copyOf(gateways);
        nameservers = List.copyOf(nameservers);
        searchDomains = List.copyOf(searchDomains);
    }

    /**
     * The first assigned address without its prefix length, which is what a caller connecting to
     * the container needs.
     *
     * @return the address, or {@code null} if the network assigned none
     */
    public String primaryAddress() {
        if (addresses.isEmpty()) {
            return null;
        }
        String cidr = addresses.get(0);
        int slash = cidr.indexOf('/');
        return slash < 0 ? cidr : cidr.substring(0, slash);
    }

    /**
     * Renders the DNS configuration as the contents of a {@code resolv.conf}.
     *
     * @return the file contents, empty if there is no DNS configuration to write
     */
    public String toResolvConf() {
        if (nameservers.isEmpty() && searchDomains.isEmpty() && domain == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String nameserver : nameservers) {
            out.append("nameserver ").append(nameserver).append('\n');
        }
        if (domain != null) {
            out.append("domain ").append(domain).append('\n');
        }
        if (!searchDomains.isEmpty()) {
            out.append("search ").append(String.join(" ", searchDomains)).append('\n');
        }
        return out.toString();
    }
}
