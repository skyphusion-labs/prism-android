package org.skyphusion.prism

/**
 * Identity-addressed edits over a transcript-like list.
 *
 * A list POSITION captured before an asynchronous request and used after it is not a reference
 * to a turn; it is a reference to a slot. When the list is cleared and refilled with a different
 * conversation the slot is still perfectly valid, so an index write succeeds -- silently, into
 * somebody else's reply. When the list is merely cleared, the same write throws.
 *
 * Addressing by id removes both states rather than testing for them: an id minted for one turn
 * does not occur in another conversation at all, so the write has nowhere wrong to land, and a
 * missing id is a no-op rather than an exception. The index is resolved at the instant of the
 * write and never held across a suspension point.
 *
 * See prism-android#37.
 */
object Transcript {
  /** Position of the item carrying [id], or -1. Resolved fresh at every call, never cached. */
  fun <T> indexOfId(items: List<T>, id: String, idOf: (T) -> String): Int {
    for (i in items.indices) {
      if (idOf(items[i]) == id) return i
    }
    return -1
  }

  /** The item carrying [id], or null once it has left the list. */
  fun <T> findById(items: List<T>, id: String, idOf: (T) -> String): T? {
    val i = indexOfId(items, id, idOf)
    return if (i < 0) null else items[i]
  }

  /**
   * Replace the item carrying [id] with `transform(it)`.
   *
   * Returns false, and mutates nothing, when no item carries [id]. Callers that need to know
   * whether the target survived can read the result; callers that simply want "write it if it
   * is still there" can ignore it.
   */
  fun <T> updateById(
    items: MutableList<T>,
    id: String,
    idOf: (T) -> String,
    transform: (T) -> T,
  ): Boolean {
    val i = indexOfId(items, id, idOf)
    if (i < 0) return false
    items[i] = transform(items[i])
    return true
  }

  /** Remove the item carrying [id]. Returns false, and mutates nothing, when it is already gone. */
  fun <T> removeById(items: MutableList<T>, id: String, idOf: (T) -> String): Boolean {
    val i = indexOfId(items, id, idOf)
    if (i < 0) return false
    items.removeAt(i)
    return true
  }
}
