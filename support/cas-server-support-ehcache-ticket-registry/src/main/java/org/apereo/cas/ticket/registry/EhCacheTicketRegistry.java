package org.apereo.cas.ticket.registry;

import net.sf.ehcache.Cache;
import net.sf.ehcache.Element;
import net.sf.ehcache.config.CacheConfiguration;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apereo.cas.CipherExecutor;
import org.apereo.cas.ticket.Ticket;
import org.springframework.util.Assert;

import javax.annotation.PostConstruct;
import java.util.Collection;
import java.util.stream.Collectors;

/**
 * <p>
 * <a href="http://ehcache.org/">Ehcache</a> based distributed ticket registry.
 * </p>
 *
 * @author <a href="mailto:cleclerc@xebia.fr">Cyrille Le Clerc</a>
 * @author Adam Rybicki
 * @author Andrew Tillinghast
 * @since 3.5
 */
public class EhCacheTicketRegistry extends AbstractTicketRegistry {
    
    private Cache ehcacheTicketsCache;
    
    /**
     * Instantiates a new EhCache ticket registry.
     *
     * @param ticketCache          the ticket cache
     * @param cipher               the cipher
     */
    public EhCacheTicketRegistry(final Cache ticketCache, final CipherExecutor cipher) {
        this.ehcacheTicketsCache = ticketCache;
        setCipherExecutor(cipher);
    }

    @Override
    public void addTicket(final Ticket ticketToAdd) {
        final Ticket ticket = encodeTicket(ticketToAdd);
        final Element element = new Element(ticket.getId(), ticket);
        
        int idleValue = ticketToAdd.getExpirationPolicy().getTimeToIdle().intValue();
        if (idleValue <= 0) {
            idleValue = ticketToAdd.getExpirationPolicy().getTimeToLive().intValue();
        }
        if (idleValue <= 0) {
            idleValue = Integer.MAX_VALUE;
        }
        element.setTimeToIdle(idleValue);

        int aliveValue = ticketToAdd.getExpirationPolicy().getTimeToLive().intValue();
        if (aliveValue <= 0) {
            aliveValue = Integer.MAX_VALUE;
        }
        element.setTimeToLive(aliveValue);

        logger.debug("Adding ticket {} to the cache {} to live {} seconds and stay idle for {} seconds",
                ticket.getId(), this.ehcacheTicketsCache.getName(), aliveValue, idleValue);
        this.ehcacheTicketsCache.put(element);
    }

    /**
     * {@inheritDoc}
     * Either the element is removed from the cache
     * or it's not found in the cache and is already removed.
     * Thus the result of this op would always be true.
     */
    @Override
    public boolean deleteSingleTicket(final String ticketId) {
        final Ticket ticket = getTicket(ticketId);
        if (ticket == null) {
            logger.debug("Ticket {} cannot be retrieved from the cache", ticketId);
            return true;
        }

        if (this.ehcacheTicketsCache.remove(ticket.getId())) {
            logger.debug("Ticket {} is removed", ticket.getId());
        }
        return true;
    }

    @Override
    public long deleteAll() {
        final int size = this.ehcacheTicketsCache.getSize();
        this.ehcacheTicketsCache.removeAll();
        return size;
    }

    @Override
    public Ticket getTicket(final String ticketIdToGet) {
        final String ticketId = encodeTicketId(ticketIdToGet);
        if (ticketId == null) {
            return null;
        }

        final Element element = this.ehcacheTicketsCache.get(ticketId);
        if (element == null) {
            logger.debug("No ticket by id [{}] is found in the registry", ticketId);
            return null;
        }
        final Ticket ticket = decodeTicket((Ticket) element.getObjectValue());

        final CacheConfiguration config = new CacheConfiguration();
        config.setTimeToIdleSeconds(ticket.getExpirationPolicy().getTimeToIdle());
        config.setTimeToLiveSeconds(ticket.getExpirationPolicy().getTimeToLive());
        
        if (element.isExpired(config) || ticket.isExpired()) {
            logger.debug("Ticket {} has expired", ticket.getId());
            this.ehcacheTicketsCache.evictExpiredElements();
            this.ehcacheTicketsCache.flush();
            return null;
        }
        
        return ticket;
    }

    @Override
    public Collection<Ticket> getTickets() {
        final Collection<Element> cacheTickets =
                this.ehcacheTicketsCache.getAll(this.ehcacheTicketsCache.getKeysWithExpiryCheck()).values();
        return decodeTickets(cacheTickets.stream().map(e -> (Ticket) e.getObjectValue()).collect(Collectors.toList()));
    }

    @Override
    public Ticket updateTicket(final Ticket ticket) {
        addTicket(ticket);
        return ticket;
    }
    
    /**
     * Init.
     */
    @PostConstruct
    public void init() {
        logger.info("Setting up Ehcache Ticket Registry...");

        Assert.notNull(this.ehcacheTicketsCache, "Ehcache Tickets cache cannot nbe null");
        if (logger.isDebugEnabled()) {
            final CacheConfiguration config = this.ehcacheTicketsCache.getCacheConfiguration();
            logger.debug("TicketCache.maxEntriesLocalHeap={}", config.getMaxEntriesLocalHeap());
            logger.debug("TicketCache.maxEntriesLocalDisk={}", config.getMaxEntriesLocalDisk());
            logger.debug("TicketCache.maxEntriesInCache={}", config.getMaxEntriesInCache());
            logger.debug("TicketCache.persistenceConfiguration={}", config.getPersistenceConfiguration().getStrategy());
            logger.debug("TicketCache.synchronousWrites={}", config.getPersistenceConfiguration().getSynchronousWrites());
            logger.debug("TicketCache.timeToLive={}", config.getTimeToLiveSeconds());
            logger.debug("TicketCache.timeToIdle={}", config.getTimeToIdleSeconds());
            logger.debug("TicketCache.cacheManager={}", this.ehcacheTicketsCache.getCacheManager().getName());
        }
    }
    
    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .appendSuper(super.toString())
                .append("ehcacheTicketsCache", this.ehcacheTicketsCache)
                .toString();
    }
}
