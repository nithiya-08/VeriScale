// Minimal IndexedDB wrapper for the offline field app.
// Stores: assignments (downloaded work), pending (inspections waiting to sync), meta (key/value).
'use strict';

const IDB = {
  _db: null,

  open() {
    if (IDB._db) return Promise.resolve(IDB._db);
    return new Promise((resolve, reject) => {
      const req = indexedDB.open('lm-officer', 1);
      req.onupgradeneeded = () => {
        const db = req.result;
        db.createObjectStore('assignments', { keyPath: 'assignmentId' });
        db.createObjectStore('pending', { keyPath: 'clientUuid' });
        db.createObjectStore('meta', { keyPath: 'key' });
      };
      req.onsuccess = () => { IDB._db = req.result; resolve(IDB._db); };
      req.onerror = () => reject(req.error);
    });
  },

  async _tx(store, mode, fn) {
    const db = await IDB.open();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(store, mode);
      const result = fn(tx.objectStore(store));
      tx.oncomplete = () => resolve(result && 'result' in result ? result.result : undefined);
      tx.onerror = () => reject(tx.error);
      tx.onabort = () => reject(tx.error || new Error('Storage full or blocked'));
    });
  },

  put(store, value) { return IDB._tx(store, 'readwrite', s => s.put(value)); },
  get(store, key) { return IDB._tx(store, 'readonly', s => s.get(key)); },
  all(store) { return IDB._tx(store, 'readonly', s => s.getAll()); },
  delete(store, key) { return IDB._tx(store, 'readwrite', s => s.delete(key)); },
  clear(store) { return IDB._tx(store, 'readwrite', s => s.clear()); },

  /** Replaces all downloaded assignments in one transaction. */
  async replaceAssignments(list) {
    const db = await IDB.open();
    return new Promise((resolve, reject) => {
      const tx = db.transaction('assignments', 'readwrite');
      const s = tx.objectStore('assignments');
      s.clear();
      list.forEach(a => s.put(a));
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  },
};
