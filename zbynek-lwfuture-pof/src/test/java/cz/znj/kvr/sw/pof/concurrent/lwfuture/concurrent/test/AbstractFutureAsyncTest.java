package cz.znj.kvr.sw.pof.concurrent.lwfuture.concurrent.test;

import cz.znj.kvr.sw.pof.concurrent.lwfuture.concurrent.SettableFuture;
import org.junit.Assert;
import org.junit.Test;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;


public class AbstractFutureAsyncTest
{
	protected static class ThreadGroup implements AutoCloseable
	{
		public                          ThreadGroup(int threadCount, int actionCount)
		{
			result = new long[threadCount][actionCount];
		}

		public void                     assertResult()
		{
			for (int i = 0; i < result.length; ++i) {
				for (int j = 0; j < result[i].length; ++j) {
					Assert.assertEquals(1, result[i][j]);
				}
			}
		}

		public void                     init(int count)
		{
			fineBarrier = new AtomicBoolean(false);
			threadBarrier = new CyclicBarrier(count, () -> {
				try {
					Thread.sleep(10);
				}
				catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
				fineBarrier.set(true);
			});
			threads = new LinkedList<>();
		}

		public void                     startGroupThreads(Function<Integer, Void> runner)
		{
			for (int i = 0; i < result.length; ++i) {
				final int id = i;
				startThread(() -> runner.apply(id));
			}
		}

		public Thread                   startThread(Runnable runner)
		{
			Thread thread = new Thread(() -> {
				try {
					threadBarrier.await();
				}
				catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
				catch (BrokenBarrierException e) {
					throw new RuntimeException(e);
				}
				runner.run();
			});
			thread.start();
			threads.add(thread);
			return thread;
		}

		public void                     waitThreads()
		{
			for (Thread thread: threads) {
				try {
					thread.join();
				}
				catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
			}
			threads = null;
		}

		@Override
		public void                     close()
		{
			if (threads != null) {
				for (Thread thread : threads)
					thread.interrupt();
				waitThreads();
			}
		}

		public long[][]                 result;

		protected List<Thread>          threads;

		private AtomicBoolean           fineBarrier;

		private CyclicBarrier           threadBarrier;
	}

	@Test
	public void                     testSuccessMatchProcessors() throws ExecutionException, InterruptedException
	{
		for (int tries = 0; tries < 20; ++tries) {
			SettableFuture<Void> future = new SettableFuture<>();
			long hit[] = new long[2];
			try (ThreadGroup group = new ThreadGroup(Math.max(1, Runtime.getRuntime().availableProcessors()-1), 20)) {
				group.init(group.result.length+1);
				group.startGroupThreads((Integer id) -> {
					long[] r = group.result[id];
					for (int i = 0; i < r.length; ++i) {
						final int aid = i;
						future.addListener(() -> {
							r[aid] = 1;
						});
					}
					;
					return null;
				});
				group.startThread(() -> {
					future.addListener(() -> {
						hit[0] = 1;
					});
					future.set(null);
					future.addListener(() -> {
						hit[1] = 1;
					});
				});
				group.waitThreads();
				group.assertResult();
				Assert.assertArrayEquals(new long[]{1, 1}, hit);
			}
		}
	}

	@Test
	public void                     testSuccessOverloadedProcessors() throws ExecutionException, InterruptedException
	{
		for (int tries = 0; tries < 20; ++tries) {
			SettableFuture<Void> future = new SettableFuture<>();
			long hit[] = new long[2];
			try (ThreadGroup group = new ThreadGroup(Math.max(1, Runtime.getRuntime().availableProcessors()*4), 20)) {
				group.init(group.result.length+1);
				group.startGroupThreads((Integer id) -> {
					long[] r = group.result[id];
					for (int i = 0; i < r.length; ++i) {
						final int aid = i;
						future.addListener(() -> {
							r[aid] = 1;
						});
					}
					;
					return null;
				});
				group.startThread(() -> {
					future.addListener(() -> {
						hit[0] = 1;
					});
					future.set(null);
					future.addListener(() -> {
						hit[1] = 1;
					});
				});
				group.waitThreads();
				group.assertResult();
				Assert.assertArrayEquals(new long[]{1, 1}, hit);
			}
		}
	}
}
