package org.luaj.vm2.lib.jse;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Thread safe cache which keeps neither its keys nor its values alive. A value refers to its key
 * (JavaClass holds its Class), so only weak values let the key, and with it a class loader, be collected.
 * A value which was collected is made again by the caller.
 */
class WeakCache {
	private final Map map = Collections.synchronizedMap(new WeakHashMap());

	Object get(Object key) {
		WeakReference ref = (WeakReference) map.get(key);
		return ref == null? null: ref.get();
	}

	void put(Object key, Object value) {
		map.put(key, new WeakReference(value));
	}
}
