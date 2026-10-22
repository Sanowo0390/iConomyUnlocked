package io.github.townyadvanced.iconomy.util;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class AsyncReadExecutorTest {
	private static Runnable take(BlockingQueue<Runnable> queue) throws InterruptedException {
		Runnable task = queue.poll(3, TimeUnit.SECONDS);
		assertNotNull(task, "Worker did not finish");
		return task;
	}

	@Test void queryRunsOffCallerAndDuplicateRemainsBlockedUntilReply() throws Exception {
		Thread caller = Thread.currentThread();
		BlockingQueue<Runnable> replies = new LinkedBlockingQueue<>();
		AtomicReference<Thread> queryThread = new AtomicReference<>();
		AtomicReference<Thread> replyThread = new AtomicReference<>();
		try (AsyncReadExecutor executor = new AsyncReadExecutor()) {
			assertTrue(executor.submit("player", () -> { queryThread.set(Thread.currentThread()); return 42; },
				(task, retired) -> replies.add(task), result -> { assertEquals(42, result); replyThread.set(Thread.currentThread()); }, error -> fail(error)));
			Runnable reply = take(replies);
			assertNotSame(caller, queryThread.get());
			assertNull(replyThread.get());
			assertFalse(executor.submit("player", () -> 1, (task, retired) -> replies.add(task), result -> {}, error -> {}));
			reply.run();
			assertSame(caller, replyThread.get());
			assertTrue(executor.submit("player", () -> 2, (task, retired) -> replies.add(task), result -> {}, error -> {}));
		}
	}

	@Test void databaseFailureReachesErrorCallbackAndReleasesSender() throws Exception {
		BlockingQueue<Runnable> replies = new LinkedBlockingQueue<>();
		AtomicReference<Exception> failure = new AtomicReference<>();
		try (AsyncReadExecutor executor = new AsyncReadExecutor()) {
			assertTrue(executor.submit("player", () -> { throw new SQLException("offline"); },
				(task, retired) -> replies.add(task), result -> fail("unexpected success"), failure::set));
			take(replies).run();
			assertInstanceOf(SQLException.class, failure.get());
			assertTrue(executor.submit("player", () -> 1, (task, retired) -> replies.add(task), result -> {}, error -> {}));
		}
	}

	@Test void fullQueueRejectsWithoutRunningOnCaller() throws Exception {
		CountDownLatch started = new CountDownLatch(1), unblock = new CountDownLatch(1);
		AtomicBoolean rejectedRan = new AtomicBoolean();
		try (AsyncReadExecutor executor = new AsyncReadExecutor(1)) {
			assertTrue(executor.submit("first", () -> { started.countDown(); unblock.await(); return 1; },
				(task, retired) -> retired.run(), result -> {}, error -> {}));
			assertTrue(started.await(3, TimeUnit.SECONDS));
			assertTrue(executor.submit("second", () -> 2, (task, retired) -> retired.run(), result -> {}, error -> {}));
			assertFalse(executor.submit("third", () -> { rejectedRan.set(true); return 3; },
				(task, retired) -> retired.run(), result -> {}, error -> {}));
			assertFalse(rejectedRan.get());
		} finally { unblock.countDown(); }
	}

	@Test void closeSuppressesAlreadyScheduledRepliesAndRejectsNewWork() throws Exception {
		BlockingQueue<Runnable> replies = new LinkedBlockingQueue<>();
		AtomicBoolean delivered = new AtomicBoolean();
		try (AsyncReadExecutor executor = new AsyncReadExecutor()) {
			executor.submit("player", () -> 1, (task, retired) -> replies.add(task), result -> delivered.set(true), error -> fail(error));
			Runnable reply = take(replies);
			executor.close();
			reply.run();
			assertFalse(delivered.get());
			assertFalse(executor.submit("other", () -> 1, (task, retired) -> {}, result -> {}, error -> {}));
		}
	}

	@Test void shutdownInterruptsBlockedQueryAndDiscardsQueuedWork() throws Exception {
		CountDownLatch started = new CountDownLatch(1), interrupted = new CountDownLatch(1);
		AtomicBoolean queuedRan = new AtomicBoolean(), replied = new AtomicBoolean();
		try (AsyncReadExecutor executor = new AsyncReadExecutor()) {
			executor.submit("first", () -> {
				started.countDown();
				try { new CountDownLatch(1).await(); } catch (InterruptedException error) { interrupted.countDown(); throw error; }
				return 1;
			}, (task, retired) -> replied.set(true), result -> {}, error -> {});
			assertTrue(started.await(3, TimeUnit.SECONDS));
			executor.submit("second", () -> { queuedRan.set(true); return 2; }, (task, retired) -> replied.set(true), result -> {}, error -> {});
			executor.close();
			assertTrue(interrupted.await(3, TimeUnit.SECONDS));
			assertFalse(queuedRan.get());
			assertFalse(replied.get());
		}
	}

	@Test void retiredSenderCanSubmitAgain() throws Exception {
		BlockingQueue<Runnable> retirements = new LinkedBlockingQueue<>();
		try (AsyncReadExecutor executor = new AsyncReadExecutor()) {
			executor.submit("player", () -> 1, (task, retired) -> retirements.add(retired), result -> fail(), error -> fail(error));
			take(retirements).run();
			assertTrue(executor.submit("player", () -> 1, (task, retired) -> retired.run(), result -> {}, error -> {}));
		}
	}
}
