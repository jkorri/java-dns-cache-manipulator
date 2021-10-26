package com.alibaba.dcm.internal;

import com.alibaba.dcm.DnsCache;
import com.alibaba.dcm.DnsCacheEntry;

import javax.annotation.Nullable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListSet;

import static com.alibaba.dcm.internal.InetAddressCacheUtil.isDnsCacheEntryExpired;
import static com.alibaba.dcm.internal.InetAddressCacheUtil.toInetAddressArray;

/**
 * Util class to manipulate dns cache {@link InetAddress#cache}.
 *
 * @author antfling (ding_zhengang at hithinksoft dot com)
 * @author Jerry Lee (oldratlee at gmail dot com)
 * @since 1.6.0
 */
public class InetAddressJdk9PlusCacheUtil {
    /**
     * {@link InetAddress.CachedAddresses}
     * <p>
     * For jdk9+,
     * <ul>
     * <li>need not convert host to lowercase.<br>
     *     see {@link InetAddress.CachedAddresses#CachedAddresses}.
     * <li>{@code final long expiryTime; // time of expiry (in terms of System.nanoTime()) }<br>
     *     see {@link InetAddress.CachedAddresses#expiryTime}.
     * </ul>
     */
    public static void setInetAddressCache(String host, String[] ips, long expiration)
            throws UnknownHostException, IllegalAccessException, InstantiationException, InvocationTargetException, ClassNotFoundException, NoSuchFieldException {
        Object cachedAddresses = newCachedAddresses(host, ips, expiration);

        getCacheFieldOfInetAddress().put(host, cachedAddresses);
        addToExpirySetFieldOfInetAddress(cachedAddresses);
    }

    static Object newCachedAddresses(String host, String[] ips, long expiration)
            throws ClassNotFoundException, UnknownHostException, IllegalAccessException, InvocationTargetException, InstantiationException {
        Class<?> clazz = Class.forName(inetAddress$CachedAddresses_ClassName);
        // InetAddress.CachedAddresses has only a constructor:
        // - for jdk 9-jdk12, constructor signature is  CachedAddresses(String host, InetAddress[] inetAddresses, long expiryTime)
        // code in jdk 9-jdk12:
        //  http://hg.openjdk.java.net/jdk9/jdk9/jdk/file/65464a307408/src/java.base/share/classes/java/net/InetAddress.java#783
        Constructor<?> constructor = clazz.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(host, toInetAddressArray(host, ips), expiration);
    }

    /**
     * @param cachedAddresses add to expirySet
     */
    static void addToExpirySetFieldOfInetAddress(Object cachedAddresses) throws NoSuchFieldException, IllegalAccessException {
        NavigableSet<Object> expirySetFieldOfInetAddress = getExpirySetFieldOfInetAddress();
        expirySetFieldOfInetAddress.add(cachedAddresses);
    }

    public static void removeInetAddressCache(String host) throws NoSuchFieldException, IllegalAccessException {
        getCacheFieldOfInetAddress().remove(host);
        removeExpirySetFieldOfInetAddressByHost(host);
    }

    /**
     * @param host remove this host from expirySet
     */
    static void removeExpirySetFieldOfInetAddressByHost(String host) throws NoSuchFieldException, IllegalAccessException {
        NavigableSet<Object> expirySetFieldOfInetAddress = getExpirySetFieldOfInetAddress();
        for (Iterator<Object> iterator = expirySetFieldOfInetAddress.iterator(); iterator.hasNext(); ) {
            Object cachedAddresses = iterator.next();
            if (getHostOfInetAddress$CacheAddress(cachedAddresses).equals(host)) {
                iterator.remove();
            }
        }
    }

    static volatile Field hostFieldOfInetAddress$CacheAddress = null;

    /**
     * {@link InetAddress.CachedAddresses.host}
     */
    static String getHostOfInetAddress$CacheAddress(Object cachedAddresses) throws NoSuchFieldException, IllegalAccessException {
        if (hostFieldOfInetAddress$CacheAddress == null) {
            synchronized (InetAddressJdk9PlusCacheUtil.class) {
                if (hostFieldOfInetAddress$CacheAddress == null) {
                    Class<?> clazz = cachedAddresses.getClass();
                    hostFieldOfInetAddress$CacheAddress = clazz.getDeclaredField("host");
                    hostFieldOfInetAddress$CacheAddress.setAccessible(true);
                }
            }
        }
        return (String) hostFieldOfInetAddress$CacheAddress.get(cachedAddresses);
    }

    /**
     * return {@link InetAddress#cache}:
     * <ul>
     * <li>is {@code ConcurrentHashMap<String, Addresses>} type and thread-safe.
     * <li>contains values of type interface {@link InetAddress.Addresses}.
     * </ul>
     */
    @SuppressWarnings("unchecked")
    static ConcurrentMap<String, Object> getCacheFieldOfInetAddress() throws NoSuchFieldException, IllegalAccessException {
        return (ConcurrentMap<String, Object>) getCacheAndExpirySetFieldOfInetAddress0()[0];
    }

    /**
     * @return {@link InetAddress#expirySet}, is {@code ConcurrentSkipListSet<CachedAddresses>} type and thread-safe.
     */
    @SuppressWarnings("unchecked")
    static ConcurrentSkipListSet<Object> getExpirySetFieldOfInetAddress() throws NoSuchFieldException, IllegalAccessException {
        return (ConcurrentSkipListSet<Object>) getCacheAndExpirySetFieldOfInetAddress0()[1];
    }

    static volatile Object[] ADDRESS_CACHE_AND_EXPIRY_SET = null;

    /**
     * @return {@link InetAddress#cache} and {@link InetAddress#expirySet}
     */
    static Object[] getCacheAndExpirySetFieldOfInetAddress0() throws NoSuchFieldException, IllegalAccessException {
        if (ADDRESS_CACHE_AND_EXPIRY_SET == null) {
            synchronized (InetAddressJdk9PlusCacheUtil.class) {
                if (ADDRESS_CACHE_AND_EXPIRY_SET == null) {
                    final Field cacheField = InetAddress.class.getDeclaredField("cache");
                    cacheField.setAccessible(true);

                    final Field expirySetField = InetAddress.class.getDeclaredField("expirySet");
                    expirySetField.setAccessible(true);

                    ADDRESS_CACHE_AND_EXPIRY_SET = new Object[]{
                            cacheField.get(InetAddress.class),
                            expirySetField.get(InetAddress.class)
                    };
                }
            }
        }
        return ADDRESS_CACHE_AND_EXPIRY_SET;
    }

    @Nullable
    public static DnsCacheEntry getInetAddressCache(String host)
            throws NoSuchFieldException, IllegalAccessException, ClassNotFoundException {
        final Object addresses = getCacheFieldOfInetAddress().get(host);
        if (null == addresses) {
            return null;
        }

        final DnsCacheEntry dnsCacheEntry = inetAddress$Addresses2DnsCacheEntry(host, addresses);
        if (isDnsCacheEntryExpired(dnsCacheEntry.getHost())) {
            return null;
        }
        return dnsCacheEntry;
    }

    public static DnsCache listInetAddressCache()
            throws NoSuchFieldException, IllegalAccessException, ClassNotFoundException {

        final ConcurrentMap<String, Object> cache = getCacheFieldOfInetAddress();
        final List<DnsCacheEntry> retCache = new ArrayList<DnsCacheEntry>();
        for (Map.Entry<String, Object> entry : cache.entrySet()) {
            final String host = entry.getKey();

            if (isDnsCacheEntryExpired(host)) { // exclude expired entries!
                continue;
            }
            DnsCacheEntry dnsCacheEntry = inetAddress$Addresses2DnsCacheEntry(host, entry.getValue());
            if (dnsCacheEntry.getIps().length == 0) {
                retCache.add(dnsCacheEntry);
            }
        }

        final NavigableSet<Object> expirySet = getExpirySetFieldOfInetAddress();
        final List<DnsCacheEntry> retNegativeCache = new ArrayList<DnsCacheEntry>();
        for (Object addresses : expirySet) {
            final String host = getHostOfInetAddress$CacheAddress(addresses);
            DnsCacheEntry dnsCacheEntry = inetAddress$Addresses2DnsCacheEntry(host, addresses);
            if (dnsCacheEntry.getIps().length == 0) {
                retNegativeCache.add(dnsCacheEntry);
            }
        }
        return new DnsCache(retCache, retNegativeCache);
    }

    /**
     * recorder jvm start timestamp point
     */
    private static final long JVM_START_NANO_SECONDS = System.nanoTime();
    private static final long JVM_START_MILL_SECONDS = System.currentTimeMillis();

    private static final Long NEVER_EXPIRY = Long.MAX_VALUE;

    private static final String inetAddress$CachedAddresses_ClassName = "java.net.InetAddress$CachedAddresses";
    private static final String inetAddress$NameServiceAddresses_ClassName = "java.net.InetAddress$NameServiceAddresses";

    ///////////////////////////////////////////////
    // Fields of InetAddress$CachedAddresses
    ///////////////////////////////////////////////
    /**
     * {@link InetAddress.CachedAddresses.inetAddresses}
     */
    static volatile Field inetAddressesFieldOfInetAddress$CacheAddress = null;
    /**
     * {@link InetAddress.CachedAddresses.expiryTime}
     */
    static volatile Field expiryTimeFieldOfInetAddress$CacheAddress = null;

    ///////////////////////////////////////////////
    // Fields of InetAddress$NameServiceAddresses
    ///////////////////////////////////////////////
    /**
     * {@link InetAddress.NameServiceAddresses.reqAddr}
     */
    static volatile Field reqAddrFieldOfInetAddress$NameServiceAddress = null;

    /**
     * convert {@link InetAddress.Addresses} to {@link DnsCacheEntry}
     */
    static DnsCacheEntry inetAddress$Addresses2DnsCacheEntry(String host, Object addresses)
            throws NoSuchFieldException, IllegalAccessException, ClassNotFoundException {
        final String addressesClassName = addresses.getClass().getName();

        if (reqAddrFieldOfInetAddress$NameServiceAddress == null) {
            synchronized (InetAddressJdk9PlusCacheUtil.class) {
                ///////////////////////////////////////////////
                // Fields of InetAddress$CachedAddresses
                ///////////////////////////////////////////////
                final Class<?> cachedAddresses_Class = Class.forName(inetAddress$CachedAddresses_ClassName);

                final Field inetAddressesFiled = cachedAddresses_Class.getDeclaredField("inetAddresses");
                inetAddressesFiled.setAccessible(true);
                inetAddressesFieldOfInetAddress$CacheAddress = inetAddressesFiled;

                final Field expiryTimeFiled = cachedAddresses_Class.getDeclaredField("expiryTime");
                expiryTimeFiled.setAccessible(true);
                expiryTimeFieldOfInetAddress$CacheAddress = expiryTimeFiled;

                ///////////////////////////////////////////////
                // Fields of InetAddress$NameServiceAddresses
                ///////////////////////////////////////////////
                final Class<?> nameServiceAddresses_Class = Class.forName(inetAddress$NameServiceAddresses_ClassName);

                final Field reqAddrFiled = nameServiceAddresses_Class.getDeclaredField("reqAddr");
                reqAddrFiled.setAccessible(true);
                reqAddrFieldOfInetAddress$NameServiceAddress = reqAddrFiled;
            }
        }

        final InetAddress[] inetAddressArray;
        final long expiration;
        if (addressesClassName.equals(inetAddress$CachedAddresses_ClassName)) {
            inetAddressArray = (InetAddress[]) inetAddressesFieldOfInetAddress$CacheAddress.get(addresses);

            long expirationNanos = (Long) expiryTimeFieldOfInetAddress$CacheAddress.get(addresses);
            // expiration timestamp convert
            expiration = (expirationNanos - JVM_START_NANO_SECONDS) / 1000000 + JVM_START_MILL_SECONDS;
        } else if (addressesClassName.equals(inetAddress$NameServiceAddresses_ClassName)) {
            InetAddress inetAddress = (InetAddress) reqAddrFieldOfInetAddress$NameServiceAddress.get(addresses);
            inetAddressArray = new InetAddress[]{inetAddress};

            expiration = NEVER_EXPIRY;
        } else {
            throw new IllegalStateException("JDK add new child class " + addressesClassName +
                    " for class InetAddress.Addresses, report bug for dns-cache-manipulator lib!");
        }

        final String[] ips;
        if (inetAddressArray == null) {
            ips = new String[0];
        } else {
            ips = new String[inetAddressArray.length];
            for (int i = 0; i < inetAddressArray.length; i++) {
                ips[i] = inetAddressArray[i].getHostAddress();
            }
        }

        return new DnsCacheEntry(host, ips, new Date(expiration));
    }

    public static void clearInetAddressCache() throws NoSuchFieldException, IllegalAccessException {
        getCacheFieldOfInetAddress().clear();
        getExpirySetFieldOfInetAddress().clear();
    }

    private InetAddressJdk9PlusCacheUtil() {
    }
}
