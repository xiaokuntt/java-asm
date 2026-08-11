package cn.ykccchen.script;

import cn.ykccchen.script.exception.ResourceNotFoundException;

import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.ConcurrentModificationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Thread-safe repository for precompiled, named scripts.
 *
 * <p>A reload is compiled before it becomes visible. Failed compilation leaves
 * the currently active revision unchanged. Readers observe either the complete
 * old revision or the complete new revision.</p>
 */
public final class ScriptRepository {
	private static final int STORAGE_MAGIC = 0x53435250;
	private static final int STORAGE_VERSION = 2;
	private static final int MAX_STORED_SCRIPT_COUNT = 100_000;
	private static final int MAX_STORED_SOURCE_BYTES = 100 * 1024 * 1024;
	private static final long MAX_STORED_TOTAL_BYTES = 512L * 1024 * 1024;

	private final ScriptEngine engine;
	private final AtomicReference<Map<String, ScriptRevision>> revisions =
			new AtomicReference<>(Collections.emptyMap());
	private final CopyOnWriteArrayList<Consumer<ScriptChangeEvent>> listeners = new CopyOnWriteArrayList<>();
	private final AtomicLong versionSequence = new AtomicLong();
	private final AtomicLong eventSequence = new AtomicLong();
	private final Map<String, List<ScriptRevision>> history = new LinkedHashMap<>();
	private final NavigableMap<Long, ScriptRevision> historyByVersion = new TreeMap<>();
	private final NavigableSet<ScriptRevision> historyByAge = new TreeSet<>((left, right) -> {
		int byTime = Long.compare(left.getLoadedAtMillis(), right.getLoadedAtMillis());
		return byTime != 0 ? byTime : Long.compare(left.getVersion(), right.getVersion());
	});
	private final int historyLimit;
	private final long historyTtlMillis;
	private final int maxHistoryEntries;
	private final Object mutationMonitor = new Object();
	private final Object eventMonitor = new Object();
	private final Deque<ScriptChangeEvent> pendingEvents = new ArrayDeque<>();
	private final LongAdder listenerFailures = new LongAdder();
	private Thread eventPublisher;
	private long publishedEventSequence;

	public ScriptRepository() {
		this(null, 20, 0, 10_000);
	}

	public ScriptRepository(ScriptEngine engine) {
		this(engine, 20, 0, 10_000);
	}

	public ScriptRepository(ScriptEngine engine, int historyLimit) {
		this(engine, historyLimit, 0, 10_000);
	}

	public ScriptRepository(ScriptEngine engine, int historyLimit,
			long historyTtlMillis, int maxHistoryEntries) {
		if (historyLimit <= 0) {
			throw new IllegalArgumentException("historyLimit must be greater than zero");
		}
		if (historyTtlMillis < 0) throw new IllegalArgumentException("historyTtlMillis must not be negative");
		if (maxHistoryEntries <= 0) throw new IllegalArgumentException("maxHistoryEntries must be greater than zero");
		this.engine = engine;
		this.historyLimit = historyLimit;
		this.historyTtlMillis = historyTtlMillis;
		this.maxHistoryEntries = maxHistoryEntries;
	}

	public ScriptEngine getEngine() {
		return engine;
	}

	/**
	 * Compile and atomically publish a new revision.
	 */
	public ScriptRevision reload(String name, String source) {
		String normalizedName = requireName(name);
		String requiredSource = Objects.requireNonNull(source, "source");
		Script candidate = Script.create(requiredSource, engine);
		candidate.compile();

		ScriptRevision current;
		long publishedThrough;
		synchronized (mutationMonitor) {
			Map<String, ScriptRevision> snapshot = revisions.get();
			ScriptRevision previous = snapshot.get(normalizedName);
			current = new ScriptRevision(normalizedName, requiredSource,
					versionSequence.incrementAndGet(), System.currentTimeMillis(), candidate);
			revisions.set(copyWith(snapshot, normalizedName, current));
			appendHistory(current);
			publishedThrough = enqueueEvent(new ScriptChangeEvent(previous == null ? ScriptChangeEvent.Type.ADDED
					: ScriptChangeEvent.Type.UPDATED, normalizedName, previous, current));
		}
		publishEventsThrough(publishedThrough);
		return current;
	}

	/**
	 * Optimistic reload that publishes only when the active revision still has
	 * the expected version. Use version {@code 0} when the script must not exist.
	 */
	public ScriptRevision reload(String name, long expectedVersion, String source) {
		if (expectedVersion < 0) {
			throw new IllegalArgumentException("expectedVersion must not be negative");
		}
		String normalizedName = requireName(name);
		String requiredSource = Objects.requireNonNull(source, "source");
		Script candidate = Script.create(requiredSource, engine);
		candidate.compile();

		ScriptRevision current;
		long publishedThrough;
		synchronized (mutationMonitor) {
			Map<String, ScriptRevision> snapshot = revisions.get();
			ScriptRevision previous = snapshot.get(normalizedName);
			long currentVersion = previous == null ? 0 : previous.getVersion();
			if (currentVersion != expectedVersion) {
				throw new ConcurrentModificationException("脚本版本已变化：expected="
						+ expectedVersion + ", actual=" + currentVersion);
			}
			current = new ScriptRevision(normalizedName, requiredSource,
					versionSequence.incrementAndGet(), System.currentTimeMillis(), candidate);
			revisions.set(copyWith(snapshot, normalizedName, current));
			appendHistory(current);
			publishedThrough = enqueueEvent(new ScriptChangeEvent(previous == null ? ScriptChangeEvent.Type.ADDED
					: ScriptChangeEvent.Type.UPDATED, normalizedName, previous, current));
		}
		publishEventsThrough(publishedThrough);
		return current;
	}

	/**
	 * Compile every source first, then publish the batch. No revision is changed
	 * when any source in the batch fails to compile.
	 */
	public Map<String, ScriptRevision> reloadAll(Map<String, String> sources) {
		Objects.requireNonNull(sources, "sources");
		Map<String, Script> compiled = new LinkedHashMap<>();
		Map<String, String> normalizedSources = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : sources.entrySet()) {
			String name = requireName(entry.getKey());
			String source = Objects.requireNonNull(entry.getValue(), "source");
			Script script = Script.create(source, engine);
			script.compile();
			compiled.put(name, script);
			normalizedSources.put(name, source);
		}

		Map<String, ScriptRevision> result;
		long publishedThrough = 0;
		synchronized (mutationMonitor) {
			Map<String, ScriptRevision> snapshot = revisions.get();
			Map<String, ScriptRevision> updated = new LinkedHashMap<>(snapshot);
			Map<String, ScriptRevision> previousBatch = new LinkedHashMap<>();
			Map<String, ScriptRevision> loaded = new LinkedHashMap<>();
			long loadedAtMillis = System.currentTimeMillis();
			for (Map.Entry<String, Script> entry : compiled.entrySet()) {
				String name = entry.getKey();
				previousBatch.put(name, snapshot.get(name));
				ScriptRevision current = new ScriptRevision(name, normalizedSources.get(name),
						versionSequence.incrementAndGet(), loadedAtMillis, entry.getValue());
				updated.put(name, current);
				loaded.put(name, current);
			}
			revisions.set(immutableCopy(updated));
			for (Map.Entry<String, ScriptRevision> entry : loaded.entrySet()) {
				appendHistory(entry.getValue());
				ScriptRevision previous = previousBatch.get(entry.getKey());
				publishedThrough = enqueueEvent(new ScriptChangeEvent(previous == null ? ScriptChangeEvent.Type.ADDED
						: ScriptChangeEvent.Type.UPDATED, entry.getKey(), previous, entry.getValue()));
			}
			result = Collections.unmodifiableMap(new LinkedHashMap<>(loaded));
		}
		publishEventsThrough(publishedThrough);
		return result;
	}

	public Optional<ScriptRevision> find(String name) {
		return Optional.ofNullable(revisions.get().get(requireName(name)));
	}

	public ScriptRevision get(String name) {
		String normalizedName = requireName(name);
		ScriptRevision revision = revisions.get().get(normalizedName);
		if (revision == null) {
			throw new ResourceNotFoundException("找不到脚本：" + normalizedName);
		}
		return revision;
	}

	public Set<String> getNames() {
		return Collections.unmodifiableSet(new LinkedHashSet<>(revisions.get().keySet()));
	}

	public List<ScriptRevision> getRevisions() {
		List<ScriptRevision> snapshot = new ArrayList<>(revisions.get().values());
		snapshot.sort((left, right) -> left.getName().compareTo(right.getName()));
		return Collections.unmodifiableList(snapshot);
	}

	/** Returns newest-first retained revisions for one script. */
	public List<ScriptRevision> getHistory(String name) {
		synchronized (mutationMonitor) {
			trimHistory();
			List<ScriptRevision> values = history.get(requireName(name));
			return values == null ? Collections.<ScriptRevision>emptyList() : values;
		}
	}

	/** Publishes a prior revision as a new monotonic revision without recompiling it. */
	public ScriptRevision rollback(String name, long targetVersion) {
		if (targetVersion <= 0) {
			throw new IllegalArgumentException("targetVersion must be greater than zero");
		}
		String normalizedName = requireName(name);
		ScriptRevision current;
		long publishedThrough;
		synchronized (mutationMonitor) {
			trimHistory();
			ScriptRevision target = null;
			List<ScriptRevision> retained = history.get(normalizedName);
			if (retained != null) {
				for (ScriptRevision revision : retained) {
					if (revision.getVersion() == targetVersion) {
						target = revision;
						break;
					}
				}
			}
			if (target == null) {
				throw new ResourceNotFoundException("找不到脚本历史版本：" + normalizedName + "@" + targetVersion);
			}
			Map<String, ScriptRevision> snapshot = revisions.get();
			ScriptRevision previous = snapshot.get(normalizedName);
			current = new ScriptRevision(normalizedName, target.getSource(),
					versionSequence.incrementAndGet(), System.currentTimeMillis(), target.getScript());
			revisions.set(copyWith(snapshot, normalizedName, current));
			appendHistory(current);
			publishedThrough = enqueueEvent(new ScriptChangeEvent(ScriptChangeEvent.Type.ROLLED_BACK,
					normalizedName, previous, current));
		}
		publishEventsThrough(publishedThrough);
		return current;
	}

	/** Atomically writes all active sources to one versioned repository snapshot. */
	public void save(Path file) throws IOException {
		Path target = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
		Path parent = target.getParent();
		if (parent == null) {
			throw new IllegalArgumentException("file must have a parent directory");
		}
		Files.createDirectories(parent);
		Path temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
		boolean moved = false;
		try {
			Map<String, ScriptRevision> snapshot = revisions.get();
			if (snapshot.size() > MAX_STORED_SCRIPT_COUNT) {
				throw new IOException("Too many scripts in repository snapshot: " + snapshot.size());
			}
			try (DataOutputStream output = new DataOutputStream(
					new BufferedOutputStream(Files.newOutputStream(temporary)))) {
				output.writeInt(STORAGE_MAGIC);
				output.writeInt(STORAGE_VERSION);
				output.writeInt(snapshot.size());
				CRC32 checksum = new CRC32();
				DataOutputStream payload = new DataOutputStream(new CheckedOutputStream(output, checksum));
				long totalBytes = 0;
				for (Map.Entry<String, ScriptRevision> entry : snapshot.entrySet()) {
					payload.writeUTF(entry.getKey());
					byte[] source = entry.getValue().getSource().getBytes(StandardCharsets.UTF_8);
					if (source.length > MAX_STORED_SOURCE_BYTES
							|| (totalBytes += source.length) > MAX_STORED_TOTAL_BYTES) {
						throw new IOException("Script repository snapshot exceeds storage limits");
					}
					payload.writeInt(source.length);
					payload.write(source);
				}
				payload.flush();
				output.writeLong(checksum.getValue());
			}
			try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
				channel.force(true);
			}
			try {
				Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
						StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException ignored) {
				Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
			}
			moved = true;
		} finally {
			if (!moved) {
				Files.deleteIfExists(temporary);
			}
		}
	}

	/** Reads, compiles, then atomically publishes every source in a snapshot. */
	public Map<String, ScriptRevision> restore(Path file) throws IOException {
		Path sourceFile = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
		Map<String, String> sources = new LinkedHashMap<>();
		try (DataInputStream input = new DataInputStream(
				new BufferedInputStream(Files.newInputStream(sourceFile)))) {
			if (input.readInt() != STORAGE_MAGIC) {
				throw new IOException("Unsupported script repository snapshot");
			}
			int version = input.readInt();
			if (version != 1 && version != STORAGE_VERSION) {
				throw new IOException("Unsupported script repository snapshot version: " + version);
			}
			int count = input.readInt();
			if (count < 0 || count > MAX_STORED_SCRIPT_COUNT) {
				throw new IOException("Invalid script count: " + count);
			}
			if (version == 1) {
				readSnapshotEntries(input, count, sources);
			} else {
				CRC32 checksum = new CRC32();
				DataInputStream payload = new DataInputStream(new CheckedInputStream(input, checksum));
				readSnapshotEntries(payload, count, sources);
				long expectedChecksum = input.readLong();
				if (expectedChecksum != checksum.getValue()) {
					throw new IOException("Script repository snapshot checksum mismatch");
				}
			}
			if (input.read() != -1) {
				throw new IOException("Unexpected trailing repository data");
			}
		} catch (EOFException exception) {
			throw new IOException("Truncated script repository snapshot", exception);
		}
		return replaceAll(sources);
	}

	private static void readSnapshotEntries(DataInputStream input, int count,
			Map<String, String> sources) throws IOException {
		long totalBytes = 0;
		for (int index = 0; index < count; index++) {
			String name = requireName(input.readUTF());
			int length = input.readInt();
			if (length < 0 || length > MAX_STORED_SOURCE_BYTES
					|| (totalBytes += length) > MAX_STORED_TOTAL_BYTES) {
				throw new IOException("Invalid source length for " + name + ": " + length);
			}
			byte[] bytes = new byte[length];
			input.readFully(bytes);
			if (sources.put(name, new String(bytes, StandardCharsets.UTF_8)) != null) {
				throw new IOException("Duplicate script name: " + name);
			}
		}
	}

	public Object execute(String name, ScriptContext context) {
		return get(name).execute(context);
	}

	public boolean remove(String name) {
		return remove(name, false);
	}

	public boolean remove(String name, boolean purgeHistory) {
		String normalizedName = requireName(name);
		long publishedThrough;
		synchronized (mutationMonitor) {
			Map<String, ScriptRevision> snapshot = revisions.get();
			ScriptRevision previous = snapshot.get(normalizedName);
			if (previous == null) {
				if (purgeHistory) purgeHistoryInternal(normalizedName);
				return false;
			}
			Map<String, ScriptRevision> updated = new LinkedHashMap<>(snapshot);
			updated.remove(normalizedName);
			revisions.set(immutableCopy(updated));
			if (purgeHistory) purgeHistoryInternal(normalizedName);
			publishedThrough = enqueueEvent(new ScriptChangeEvent(ScriptChangeEvent.Type.REMOVED,
					normalizedName, previous, null));
		}
		publishEventsThrough(publishedThrough);
		return true;
	}

	public int purgeHistory(String name) {
		synchronized (mutationMonitor) {
			return purgeHistoryInternal(requireName(name));
		}
	}

	public int purgeAllHistory() {
		synchronized (mutationMonitor) {
			int count = historyByVersion.size();
			history.clear();
			historyByVersion.clear();
			historyByAge.clear();
			return count;
		}
	}

	public Registration addChangeListener(Consumer<ScriptChangeEvent> listener) {
		Consumer<ScriptChangeEvent> requiredListener = Objects.requireNonNull(listener, "listener");
		listeners.add(requiredListener);
		return Registration.of(() -> listeners.remove(requiredListener));
	}

	/** Returns the number of listener runtime failures isolated by this repository. */
	public long getListenerFailureCount() {
		return listenerFailures.sum();
	}

	private long enqueueEvent(ScriptChangeEvent event) {
		ScriptChangeEvent sequenced = new ScriptChangeEvent(eventSequence.incrementAndGet(),
				System.currentTimeMillis(), event.getType(), event.getName(),
				event.getPreviousRevision(), event.getCurrentRevision());
		synchronized (eventMonitor) {
			pendingEvents.addLast(sequenced);
			eventMonitor.notifyAll();
		}
		return sequenced.getSequence();
	}

	private void publishEventsThrough(long targetSequence) {
		if (targetSequence == 0) return;
		boolean interrupted = false;
		try {
			while (true) {
				synchronized (eventMonitor) {
					if (publishedEventSequence >= targetSequence) return;
					if (eventPublisher == null) {
						eventPublisher = Thread.currentThread();
					} else if (eventPublisher == Thread.currentThread()) {
						// A listener performed a reentrant mutation. The outer drain will
						// deliver its queued event after every observer sees the current one.
						return;
					} else {
						try {
							eventMonitor.wait();
						} catch (InterruptedException exception) {
							interrupted = true;
						}
						continue;
					}
				}
				drainEvents();
			}
		} finally {
			if (interrupted) Thread.currentThread().interrupt();
		}
	}

	private void drainEvents() {
		try {
			while (true) {
				ScriptChangeEvent next;
				synchronized (eventMonitor) {
					next = pendingEvents.pollFirst();
					if (next == null) return;
				}
				try {
					for (Consumer<ScriptChangeEvent> listener : listeners) {
						try {
							listener.accept(next);
						} catch (RuntimeException ignored) {
							listenerFailures.increment();
						}
					}
				} finally {
					synchronized (eventMonitor) {
						publishedEventSequence = Math.max(publishedEventSequence, next.getSequence());
						eventMonitor.notifyAll();
					}
				}
			}
		} finally {
			synchronized (eventMonitor) {
				if (eventPublisher == Thread.currentThread()) eventPublisher = null;
				eventMonitor.notifyAll();
			}
		}
	}

	private Map<String, ScriptRevision> replaceAll(Map<String, String> sources) {
		Map<String, Script> compiled = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : sources.entrySet()) {
			Script script = Script.create(entry.getValue(), engine);
			script.compile();
			compiled.put(entry.getKey(), script);
		}
		Map<String, ScriptRevision> result;
		long publishedThrough = 0;
		synchronized (mutationMonitor) {
			Map<String, ScriptRevision> previous = revisions.get();
			Map<String, ScriptRevision> loaded = new LinkedHashMap<>();
			long loadedAtMillis = System.currentTimeMillis();
			for (Map.Entry<String, Script> entry : compiled.entrySet()) {
				String name = entry.getKey();
				loaded.put(name, new ScriptRevision(name, sources.get(name),
						versionSequence.incrementAndGet(), loadedAtMillis, entry.getValue()));
			}
			revisions.set(immutableCopy(loaded));
			for (Map.Entry<String, ScriptRevision> entry : previous.entrySet()) {
				if (!loaded.containsKey(entry.getKey())) {
					publishedThrough = enqueueEvent(new ScriptChangeEvent(ScriptChangeEvent.Type.REMOVED,
							entry.getKey(), entry.getValue(), null));
				}
			}
			for (Map.Entry<String, ScriptRevision> entry : loaded.entrySet()) {
				appendHistory(entry.getValue());
				ScriptRevision prior = previous.get(entry.getKey());
				publishedThrough = enqueueEvent(new ScriptChangeEvent(prior == null ? ScriptChangeEvent.Type.ADDED
						: ScriptChangeEvent.Type.UPDATED, entry.getKey(), prior, entry.getValue()));
			}
			result = Collections.unmodifiableMap(new LinkedHashMap<>(loaded));
		}
		publishEventsThrough(publishedThrough);
		return result;
	}

	private void appendHistory(ScriptRevision revision) {
		List<ScriptRevision> existing = history.get(revision.getName());
		List<ScriptRevision> updated = existing == null
				? new ArrayList<ScriptRevision>() : new ArrayList<>(existing);
		updated.add(0, revision);
		historyByVersion.put(revision.getVersion(), revision);
		historyByAge.add(revision);
		if (updated.size() > historyLimit) {
			ScriptRevision removed = updated.remove(updated.size() - 1);
			historyByVersion.remove(removed.getVersion());
			historyByAge.remove(removed);
		}
		history.put(revision.getName(), Collections.unmodifiableList(updated));
		trimHistory();
	}

	private void trimHistory() {
		long cutoff = historyTtlMillis == 0 ? Long.MIN_VALUE
				: System.currentTimeMillis() - historyTtlMillis;
		while (!historyByAge.isEmpty() && historyByAge.first().getLoadedAtMillis() < cutoff) {
			removeHistoryRevision(historyByAge.first().getVersion());
		}
		while (historyByVersion.size() > maxHistoryEntries) {
			removeHistoryRevision(historyByAge.first().getVersion());
		}
	}

	private int purgeHistoryInternal(String name) {
		List<ScriptRevision> removed = history.remove(name);
		if (removed == null) return 0;
		for (ScriptRevision revision : removed) {
			historyByVersion.remove(revision.getVersion());
			historyByAge.remove(revision);
		}
		return removed.size();
	}

	private void removeHistoryRevision(long version) {
		ScriptRevision removed = historyByVersion.remove(version);
		if (removed == null) return;
		historyByAge.remove(removed);
		List<ScriptRevision> values = history.get(removed.getName());
		if (values == null) return;
		List<ScriptRevision> retained = new ArrayList<>(values.size());
		for (ScriptRevision revision : values) {
			if (revision.getVersion() != version) retained.add(revision);
		}
		if (retained.isEmpty()) history.remove(removed.getName());
		else history.put(removed.getName(), Collections.unmodifiableList(retained));
	}

	private static String requireName(String name) {
		String value = Objects.requireNonNull(name, "name").trim();
		if (value.isEmpty()) {
			throw new IllegalArgumentException("name must not be blank");
		}
		return value;
	}

	private static Map<String, ScriptRevision> copyWith(Map<String, ScriptRevision> source,
			String name, ScriptRevision revision) {
		Map<String, ScriptRevision> updated = new LinkedHashMap<>(source);
		updated.put(name, revision);
		return immutableCopy(updated);
	}

	private static Map<String, ScriptRevision> immutableCopy(Map<String, ScriptRevision> source) {
		return Collections.unmodifiableMap(new LinkedHashMap<>(source));
	}
}
