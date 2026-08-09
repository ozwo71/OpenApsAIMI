package app.aaps.plugins.aps.openAPSAIMI.autodrive.learning

/**
 * Serialises every access to `autodrive_dataset.csv`.
 *
 * Two writers share that file and they are not compatible:
 *
 * - [AutodriveDataLake] appends one row per Autodrive tick with a `FileWriter` in append mode.
 * - [AutodriveDataBackfiller] reads the whole file, writes a temporary copy with the outcome columns
 *   filled, then renames it over the original.
 *
 * A row appended between the backfiller's read and its rename lands in a file that is about to be
 * replaced, so it is lost — silently, and precisely on the ticks the model most needs. They run on
 * different WorkManager threads, so this is not theoretical.
 *
 * A process-wide monitor is enough: both operations are short, and the file has a single owner
 * process. It is deliberately not a `ReentrantReadWriteLock` — the backfiller's read and write are
 * one indivisible transaction, so a read lock would not be safe to upgrade.
 */
internal object AutodriveDatasetLock {

    private val monitor = Any()

    /** Runs [block] with exclusive access to the dataset file. */
    fun <T> withDataset(block: () -> T): T = synchronized(monitor) { block() }
}
