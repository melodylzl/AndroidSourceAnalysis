/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BEGIN LAYOUTLIB CHANGE
 * This is a custom version that doesn't use the non standard LinkedHashMap#eldest.
 * END LAYOUTLIB CHANGE
 *
 * A cache that holds strong references to a limited number of values. Each time
 * a value is accessed, it is moved to the head of a queue. When a value is
 * added to a full cache, the value at the end of that queue is evicted and may
 * become eligible for garbage collection.
 *
 * <p>If your cached values hold resources that need to be explicitly released,
 * override {@link #entryRemoved}.
 *
 * <p>If a cache miss should be computed on demand for the corresponding keys,
 * override {@link #create}. This simplifies the calling code, allowing it to
 * assume a value will always be returned, even when there's a cache miss.
 *
 * <p>By default, the cache size is measured in the number of entries. Override
 * {@link #sizeOf} to size the cache in different units. For example, this cache
 * is limited to 4MiB of bitmaps:
 * <pre>   {@code
 *   int cacheSize = 4 * 1024 * 1024; // 4MiB
 *   LruCache<String, Bitmap> bitmapCache = new LruCache<String, Bitmap>(cacheSize) {
 *       protected int sizeOf(String key, Bitmap value) {
 *           return value.getByteCount();
 *       }
 *   }}</pre>
 *
 * <p>This class is thread-safe. Perform multiple cache operations atomically by
 * synchronizing on the cache: <pre>   {@code
 *   synchronized (cache) {
 *     if (cache.get(key) == null) {
 *         cache.put(key, value);
 *     }
 *   }}</pre>
 *
 * <p>This class does not allow null to be used as a key or value. A return
 * value of null from {@link #get}, {@link #put} or {@link #remove} is
 * unambiguous: the key was not in the cache.
 *
 * <p>This class appeared in Android 3.1 (Honeycomb MR1); it's available as part
 * of <a href="http://developer.android.com/sdk/compatibility-library.html">Android's
 * Support Package</a> for earlier releases.
 */
public class LruCache<K, V> {
    /** LruCache内部存储数据使用的数据结构就是LinkedHashMap
     *  LinkedHashMap是一个双向链接结构的HashMap */
    private final LinkedHashMap<K, V> map;

    /** 已缓存的大小，并不一定是指存储数据的数量 */
    private int size;
    /** 最多可缓存的大小 */
    private int maxSize;

    /** put的次数 */
    private int putCount;
    /** create()方法所调用的次数 */
    private int createCount;
    /** 回收数据空间的次数 */
    private int evictionCount;
    /** 命中成功的次数 */
    private int hitCount;
    /** 命中失败的次数 */
    private int missCount;

    /**
     * @param maxSize for caches that do not override {@link #sizeOf}, this is
     *     the maximum number of entries in the cache. For all other caches,
     *     this is the maximum sum of the sizes of the entries in this cache.
     */
    /** 唯一的构造函数，需要传入缓存的最大值 */
    public LruCache(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize <= 0");
        }
        this.maxSize = maxSize;
        /** 初始化LinkedHashMap
         *  第一个参数：初始容量，传0的话HashMap默认初始容量为16
         *  第二个参数：负载因子，0.75f,表示LinkedHashMap在当前容量的75%已填充的情况下扩容
         *  第三个参数，accessOrder,为true时，LinkedHashMap的数据按访问顺序排序，为flase时，按插入顺序排序
         *            正是因为LinkedHashMap这数据排序的特性，LruCache很轻易实现了LRU算法的缓存*/
        this.map = new LinkedHashMap<K, V>(0, 0.75f, true);
    }

    /**
     * Sets the size of the cache.
     * @param maxSize The new maximum size.
     *
     * @hide
     */
    /** 调整缓存的容量最大值 */
    public void resize(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize <= 0");
        }

        synchronized (this) {
            this.maxSize = maxSize;
        }
        trimToSize(maxSize);
    }

    /**
     * Returns the value for {@code key} if it exists in the cache or can be
     * created by {@code #create}. If a value was returned, it is moved to the
     * head of the queue. This returns null if a value is not cached and cannot
     * be created.
     */
    public final V get(K key) {
        if (key == null) {
            throw new NullPointerException("key == null");
        }

        V mapValue;
        /** 线程同步，get,put,remove、trimToSize方法都有实现线程同步*/
        synchronized (this) {
            /** 从LinkedHashMap中获取key所对应的value,
             *  LinkedHashMap的get方法，如果获取到value不为null时，在accessOrder=true时
             *  会把<key,value>对应的节点移至链表的结尾 */
            mapValue = map.get(key);
            if (mapValue != null) {
                /** 如果mapValue不为空，命中成功次数+1*/
                hitCount++;
                /** 返回mapValue*/
                return mapValue;
            }
            /** 如果mapValue为空，命中失败次数+1*/
            missCount++;
        }

        /*
         * Attempt to create a value. This may take a long time, and the map
         * may be different when create() returns. If a conflicting value was
         * added to the map while create() was working, we leave that value in
         * the map and release the created value.
         */

        /** 当key所获取的value为空时，会调用create方法自定义创建value值，该方法需要重写，不然默认返回空*/
        V createdValue = create(key);
        if (createdValue == null) {
            return null;
        }

        synchronized (this) {
            /** 调用create方法次数+1*/
            createCount++;
            /** 把createValue存放进LinkedHashMap*/
            mapValue = map.put(key, createdValue);

            /** 如果mapValue不为空，则发生冲突，这里为什么会发生冲突，是因为当我们调用create方法（这里没有上锁）创建
             * createValue时，可能需要很长时间，当调用LruCache的数据量很大时，可能之前的key对所应的位置已经有值，那么
             * 就发生冲突了*/
            if (mapValue != null) {
                /** 发生冲突，则把旧值重新放进LinkedHashMap中，mapValue是旧值，createdValue是新值*/
                map.put(key, mapValue);
            } else {
                /** 没有发生冲突，则计算当前缓存的容量大小*/
                size += safeSizeOf(key, createdValue);
            }
        }

        if (mapValue != null) {
            /** entryRemoved的作用主要是在数据被回收了、被删除或者被覆盖的时候回调
             * 因为刚才发生冲突了，产生数据被覆盖的情况，因为调用entryRemoved方法*/
            entryRemoved(false, key, createdValue, mapValue);
            return mapValue;
        } else {
            /** 没有发生冲突，新数据添加到缓存中，因此调用trimToSize方法判断是否容量不足需要删除最近最少使用的数据*/
            trimToSize(maxSize);
            return createdValue;
        }
    }

    /**
     * Caches {@code value} for {@code key}. The value is moved to the head of
     * the queue.
     *
     * @return the previous value mapped by {@code key}.
     */
    public final V put(K key, V value) {
        if (key == null || value == null) {
            throw new NullPointerException("key == null || value == null");
        }

        /** 旧值 */
        V previous;
        synchronized (this) {
            /** put次数+1 */
            putCount++;
            /** 计算当前缓存的容量大小 */
            size += safeSizeOf(key, value);
            /** 存储新的value值，如果有旧值，则返回 */
            previous = map.put(key, value);
            if (previous != null) {
                /** 因为旧值被覆盖，所以缓存容量大小要减去旧值所的空间 */
                size -= safeSizeOf(key, previous);
            }
        }

        if (previous != null) {
            /** 发生了数据被覆盖的情况，回调entryRemoved方法 */
            entryRemoved(false, key, previous, value);
        }

        /** 重整缓存空间 */
        trimToSize(maxSize);
        /** put方法所返回的值是被覆盖的旧值或者是null */
        return previous;
    }

    /**
     * @param maxSize the maximum size of the cache before returning. May be -1
     *     to evict even 0-sized elements.
     */
    /** 根据传入参数调整缓存空间 */
    private void trimToSize(int maxSize) {
        /** 死循环，只有在size <= maxSize即当前容量大小小于最大值时，或者没有可删除的数据时跳出循环 */
        while (true) {
            K key;
            V value;
            synchronized (this) {
                /** 当前容量大小<0，或者map为空但容量大小等于0时，抛出异常 */
                if (size < 0 || (map.isEmpty() && size != 0)) {
                    throw new IllegalStateException(getClass().getName()
                            + ".sizeOf() is reporting inconsistent results!");
                }

                /** 当前容量大小小于最大值，不需要作任何调整 */
                if (size <= maxSize) {
                    break;
                }

                // BEGIN LAYOUTLIB CHANGE
                // get the last item in the linked list.
                // This is not efficient, the goal here is to minimize the changes
                // compared to the platform version.

                /** 获取表头元素，表头元素是最近最少未访问的数据，首先移除表头的元素 */
                Map.Entry<K, V> toEvict = null;
                for (Map.Entry<K, V> entry : map.entrySet()) {
                    toEvict = entry;
                }
                // END LAYOUTLIB CHANGE

                /** 如果表头元素为空，则表明表里没有可回收空间，跳出循环 */
                if (toEvict == null) {
                    break;
                }

                key = toEvict.getKey();
                value = toEvict.getValue();
                /** 从LinkedHashMap中移除 */
                map.remove(key);
                /** 当前容量大小减去移除数据所占的空间 */
                size -= safeSizeOf(key, value);
                /** 移除的数据+1 */
                evictionCount++;
            }

            /** 因为发生数据被回收了，回调entryRemoved方法 */
            entryRemoved(true, key, value, null);
        }
    }

    /**
     * Removes the entry for {@code key} if it exists.
     *
     * @return the previous value mapped by {@code key}.
     */
    public final V remove(K key) {
        if (key == null) {
            throw new NullPointerException("key == null");
        }

        /** 旧值 */
        V previous;
        synchronized (this) {
            /** 从LinkedHashMap中移除 */
            previous = map.remove(key);
            /** 如果previous不为空，则需要调整缓存容量大小 */
            if (previous != null) {
                /** 当前容量大小减去移除数据所占的空间 */
                size -= safeSizeOf(key, previous);
            }
        }

        if (previous != null) {
            /** 因为发生了数据被删除，回调entryRemoved方法 */
            entryRemoved(false, key, previous, null);
        }

        return previous;
    }

    /**
     * Called for entries that have been evicted or removed. This method is
     * invoked when a value is evicted to make space, removed by a call to
     * {@link #remove}, or replaced by a call to {@link #put}. The default
     * implementation does nothing.
     *
     * <p>The method is called without synchronization: other threads may
     * access the cache while this method is executing.
     *
     * @param evicted true if the entry is being removed to make space, false
     *     if the removal was caused by a {@link #put} or {@link #remove}.
     * @param newValue the new value for {@code key}, if it exists. If non-null,
     *     this removal was caused by a {@link #put}. Otherwise it was caused by
     *     an eviction or a {@link #remove}.
     */

    /** 当发生数据被回收、删除或者被覆盖的情况下调用
     * 第一个参数：evicted,true时表示数据是否因为空间不足被回收
     * 第二个参数：key
     * 第三个参数：oldValue,旧值
     * 第四个参数：newValue,一般在被覆盖的情况下有值，返回的是覆盖的值，其他情况为null*/
    protected void entryRemoved(boolean evicted, K key, V oldValue, V newValue) {}

    /**
     * Called after a cache miss to compute a value for the corresponding key.
     * Returns the computed value or null if no value can be computed. The
     * default implementation returns null.
     *
     * <p>The method is called without synchronization: other threads may
     * access the cache while this method is executing.
     *
     * <p>If a value for {@code key} exists in the cache when this method
     * returns, the created value will be released with {@link #entryRemoved}
     * and discarded. This can occur when multiple threads request the same key
     * at the same time (causing multiple values to be created), or when one
     * thread calls {@link #put} while another is creating a value for the same
     * key.
     */

    /** 当调用get方法获取不到对应的值时，可以覆盖create方法返回自定义的值，一般不建议重写*/
    protected V create(K key) {
        return null;
    }

    /** 计算存放当前数据后所占的容量大小 */
    private int safeSizeOf(K key, V value) {
        int result = sizeOf(key, value);
        if (result < 0) {
            throw new IllegalStateException("Negative size: " + key + "=" + value);
        }
        return result;
    }

    /**
     * Returns the size of the entry for {@code key} and {@code value} in
     * user-defined units.  The default implementation returns 1 so that size
     * is the number of entries and max size is the maximum number of entries.
     *
     * <p>An entry's size must not change while it is in the cache.
     */

    /** 一般都要覆盖该方法，该方法主要是计算当前数据所占的空间大小，如果不覆盖，则每存一个数据，则占的空间是1 */
    protected int sizeOf(K key, V value) {
        return 1;
    }

    /**
     * Clear the cache, calling {@link #entryRemoved} on each removed entry.
     */
    /** 回收所有空间 */
    public final void evictAll() {
        trimToSize(-1); // -1 will evict 0-sized elements
    }

    /**
     * For caches that do not override {@link #sizeOf}, this returns the number
     * of entries in the cache. For all other caches, this returns the sum of
     * the sizes of the entries in this cache.
     */
    /** 返回当前缓存容量大小 */
    public synchronized final int size() {
        return size;
    }

    /**
     * For caches that do not override {@link #sizeOf}, this returns the maximum
     * number of entries in the cache. For all other caches, this returns the
     * maximum sum of the sizes of the entries in this cache.
     */
    /** 返回当前缓存最大值 */
    public synchronized final int maxSize() {
        return maxSize;
    }

    /**
     * Returns the number of times {@link #get} returned a value that was
     * already present in the cache.
     */
    /** 返回命中成功的次数 */
    public synchronized final int hitCount() {
        return hitCount;
    }

    /**
     * Returns the number of times {@link #get} returned null or required a new
     * value to be created.
     */
    /** 返回命中失败的次数 */
    public synchronized final int missCount() {
        return missCount;
    }

    /**
     * Returns the number of times {@link #create(Object)} returned a value.
     */
    /** 返回调用create方法的次数 */
    public synchronized final int createCount() {
        return createCount;
    }

    /**
     * Returns the number of times {@link #put} was called.
     */
    /** 返回put的次数 */
    public synchronized final int putCount() {
        return putCount;
    }

    /**
     * Returns the number of values that have been evicted.
     */
    /** 返回回收数据空间的次数 */
    public synchronized final int evictionCount() {
        return evictionCount;
    }

    /**
     * Returns a copy of the current contents of the cache, ordered from least
     * recently accessed to most recently accessed.
     */
    public synchronized final Map<K, V> snapshot() {
        return new LinkedHashMap<K, V>(map);
    }

    @Override public synchronized final String toString() {
        int accesses = hitCount + missCount;
        int hitPercent = accesses != 0 ? (100 * hitCount / accesses) : 0;
        return String.format("LruCache[maxSize=%d,hits=%d,misses=%d,hitRate=%d%%]",
                maxSize, hitCount, missCount, hitPercent);
    }
}
