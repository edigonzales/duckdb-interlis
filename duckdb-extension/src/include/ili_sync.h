#ifndef ILI_SYNC_H
#define ILI_SYNC_H

// Portable mutex abstraction: pthread_mutex_t on POSIX, CRITICAL_SECTION on Windows.
// On POSIX, use static initializer ILI_MUTEX_INITIALIZER.
// On Windows, call ili_mutex_init() before first use.

#ifdef _WIN32
#include <windows.h>

typedef CRITICAL_SECTION ili_mutex_t;

static inline void ili_mutex_init(ili_mutex_t *m) {
    InitializeCriticalSection(m);
}

static inline void ili_mutex_lock(ili_mutex_t *m) {
    EnterCriticalSection(m);
}

static inline void ili_mutex_unlock(ili_mutex_t *m) {
    LeaveCriticalSection(m);
}

static inline void ili_mutex_destroy(ili_mutex_t *m) {
    DeleteCriticalSection(m);
}

#else
#include <pthread.h>

typedef pthread_mutex_t ili_mutex_t;

#define ILI_MUTEX_INITIALIZER PTHREAD_MUTEX_INITIALIZER

static inline void ili_mutex_init(ili_mutex_t *m) {
    (void)m;
}

static inline void ili_mutex_lock(ili_mutex_t *m) {
    pthread_mutex_lock(m);
}

static inline void ili_mutex_unlock(ili_mutex_t *m) {
    pthread_mutex_unlock(m);
}

static inline void ili_mutex_destroy(ili_mutex_t *m) {
    pthread_mutex_destroy(m);
}

#endif

#endif // ILI_SYNC_H
