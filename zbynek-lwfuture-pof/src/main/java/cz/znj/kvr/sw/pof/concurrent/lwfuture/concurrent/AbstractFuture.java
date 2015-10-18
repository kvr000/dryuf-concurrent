package cz.znj.kvr.sw.pof.concurrent.lwfuture.concurrent;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Function;


public class AbstractFuture<V> implements ListenableFuture<V>
{
	public                          AbstractFuture()
	{
		this(true);
	}

	public                          AbstractFuture(boolean isRunning)
	{
		this(isRunning ? ST_RUNNING : 0);
	}

	public                          AbstractFuture(int initialStatus)
	{
		this.status = initialStatus;
	}

	@Override
	public boolean			cancel(boolean b)
	{
		int old;
		if ((old = updateStatusFinal(ST_CANCELLED)) < ST_FINISHED) {
			if ((old&ST_RUNNING) != 0)
				interruptTask();
			if ((old&ST_DELAYED_CANCEL) == 0)
				processListenersCancelled();
			return true;
		}
		return false;
	}

	@Override
	public boolean			isCancelled()
	{
		return (status&ST_CANCELLED) != 0;
	}

	@Override
	public boolean			isDone()
	{
		return status >= ST_FINISHED;
	}

	@Override
	public V			get() throws InterruptedException, ExecutionException
	{
		for (;;) {
			try {
				return get(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
			}
			catch (TimeoutException e) {
			}
		}
	}

	@Override
	public V			get(long l, TimeUnit timeUnit) throws InterruptedException, ExecutionException, TimeoutException
	{
		int localStatus = status;
		if (localStatus < ST_FINISHED) {
			// this would hardly ever happen as we expect any get would be run by listener
			synchronized (this) {
				for (;;) {
					localStatus = status;
					if (localStatus >= ST_FINISHED)
						break;
					if (statusUpdater.compareAndSet(this, localStatus, localStatus|ST_WAITING)) {
						this.wait(timeUnit.toMillis(l));
						if (localStatus < ST_FINISHED)
							throw new TimeoutException();
					}
				}
			}
		}
		switch (localStatus) {
		case ST_CANCELLED:
			throw new CancellationException();

		case ST_FINISHED:
			if (excepted != null)
				throw new ExecutionException(excepted);
			return result;

		default:
			throw new Error("Unknown final status: "+localStatus);
		}
	}

	@Override
	public void                     setDelayedCancel()
	{
		int localStatus = 0;
		for (;;) {
			if (statusUpdater.compareAndSet(this, localStatus, localStatus|ST_DELAYED_CANCEL))
				return;
			localStatus = status;
			if (localStatus >= ST_FINISHED)
				throw new IllegalStateException("Future delayed cannot be changed once the future was started.");
		}
	}

	@Override
	public ListenableFuture<V>	addListener(final Runnable listener)
	{
		addListenerNode(new RegularListenerNode<V>() {
			@Override
			public void executeSet() {
				listener.run();
			}

			@Override
			public void executeExcepted() {
				listener.run();
			}

			@Override
			public void executeCancelled() {
				listener.run();
			}
		});
		return this;
	}

	@Override
	public ListenableFuture<V>	addListener(final Function<Future<V>, Void> listener)
	{
		addListenerNode(new RegularListenerNode<V>() {
			@Override
			public void executeSet() {
				listener.apply(AbstractFuture.this);
			}

			@Override
			public void executeExcepted() {
				listener.apply(AbstractFuture.this);
			}

			@Override
			public void executeCancelled() {
				listener.apply(AbstractFuture.this);
			}
		});
		return this;
	}

	@Override
	public ListenableFuture<V>      addListener(final FutureListener<V> listener)
	{
		addListenerNode(new RegularListenerNode<V>() {
			@Override
			public void executeSet() {
				listener.onSuccess(result);
			}

			@Override
			public void executeExcepted() {
				listener.onFailure(excepted);
			}

			@Override
			public void executeCancelled() {
				listener.onCancelled();
			}
		});
		return this;
	}

	protected void                  interruptTask()
	{
	}

	protected void                  set(V result)
	{
		this.result = result;
		if (updateStatusFinished(ST_FINISHED) < ST_FINISHED)
			processListenersSet();
	}

	protected void                  setException(Throwable ex)
	{
		this.excepted = ex;
		if (updateStatusFinished(ST_FINISHED) < ST_FINISHED)
			processListenersExcepted();
	}

	protected final boolean         setRunning()
	{
		int localStatus = 0;
		for (;;) {
			if (statusUpdater.compareAndSet(this, localStatus, localStatus|ST_RUNNING))
				return true;
			localStatus = status;
			if (localStatus >= ST_FINISHED)
				return false;
		}
	}

	private final int               updateStatusFinished(int finalStatus)
	{
		int old = updateStatusFinal(finalStatus);
		if ((old&(ST_CANCELLED|ST_DELAYED_CANCEL)) != 0)
			processListenersCancelled();
		return old;
	}

	private final int               updateStatusFinal(int finalStatus)
	{
		int localStatus = ST_RUNNING;
		for (;;) {
			int newStatus = localStatus&~(ST_RUNNING|ST_WAITING)|finalStatus;
			if (statusUpdater.compareAndSet(this, localStatus, newStatus))
				break;
			localStatus = newStatus;
			if (localStatus >= ST_FINISHED)
				return localStatus;
		}
		if ((localStatus&ST_WAITING) != 0) {
			synchronized (this) {
				notifyAll();
			}
		}
		return localStatus;
	}

	protected final void            addListenerNode(ListenerNode listenerNode)
	{
		if (listenersUpdater.compareAndSet(this, null, listenerNode))
			return;
		for (;;) {
			ListenerNode localListeners = listeners;
			if (localListeners != null) {
				switch (localListeners.getNodeType()) {
				case ListenerNode.NT_REGULAR:
					listenerNode.nextNode = localListeners;
					break;

				case ListenerNode.NT_MARKER_PROCESSING:
					// leave the next set to null
					break;

				case ListenerNode.NT_MARKER_FINISHED:
					executeLateListener(listenerNode);
					return;
				}
			}
			if (listenersUpdater.compareAndSet(this, localListeners, listenerNode))
				return;
		}
	}

	private final void              executeLateListener(ListenerNode<V> listener)
	{
		switch (status&(ST_FINISHED|ST_CANCELLED)) {
		case ST_FINISHED:
			if (excepted != null)
				listener.executeExcepted();
			else
				listener.executeSet();
			break;

		case ST_CANCELLED:
		case ST_CANCELLED|ST_FINISHED:
			listener.executeCancelled();
			break;

		default:
			throw new IllegalStateException("Unexpected status when running late listener: "+status);
		}
	}

	private final ListenerNode<V>   swapListenersQueue(ListenerNode<V> last)
	{
		ListenerNode<V> tail = last;
		ListenerNode<V> next = last.getNextNode();
		tail.nextNode = null;
		for (ListenerNode<V> current = next; current != null; current = next) {
			next = current.getNextNode();
			current.nextNode = tail;
			tail = current;
		}
		return tail;
	}

	private final void              processListenersSet()
	{
		ListenerNode lastListener = listenersUpdater.getAndSet(this, MARKER_PROCESSING);
		for (;;) {
			if (lastListener != null) {
				for (lastListener = swapListenersQueue(lastListener); lastListener != null; lastListener = lastListener.nextNode) {
					try {
						lastListener.executeSet();
					}
					catch (Exception ex) {
						// ignore exceptions from listeners
					}
				}
			}
			if (listenersUpdater.compareAndSet(this, MARKER_PROCESSING, MARKER_FINISHED))
				return;
			lastListener = listenersUpdater.getAndSet(this, MARKER_PROCESSING);
		}
	}

	private final void              processListenersExcepted()
	{
		ListenerNode lastListener = listenersUpdater.getAndSet(this, MARKER_PROCESSING);
		for (;;) {
			if (lastListener != null) {
				for (lastListener = swapListenersQueue(lastListener); lastListener != null; lastListener = lastListener.nextNode) {
					try {
						lastListener.executeExcepted();
					}
					catch (Exception ex) {
						// ignore exceptions from listeners
					}
				}
			}
			if (listenersUpdater.compareAndSet(this, MARKER_PROCESSING, MARKER_FINISHED))
				return;
			lastListener = listenersUpdater.getAndSet(this, MARKER_PROCESSING);
		}
	}

	private final void              processListenersCancelled()
	{
		ListenerNode lastListener = listenersUpdater.getAndSet(this, MARKER_PROCESSING);
		for (;;) {
			if (lastListener != null) {
				for (lastListener = swapListenersQueue(lastListener); lastListener != null; lastListener = lastListener.nextNode) {
					try {
						lastListener.executeCancelled();
					}
					catch (Exception ex) {
						// ignore exceptions from listeners
					}
				}
			}
			if (listenersUpdater.compareAndSet(this, MARKER_PROCESSING, MARKER_FINISHED))
				return;
			lastListener = listenersUpdater.getAndSet(this, MARKER_PROCESSING);
		}
	}

	protected static abstract class ListenerNode<V>
	{
		public static final int         NT_REGULAR                      = 0;
		public static final int         NT_MARKER_PROCESSING            = 1;
		public static final int         NT_MARKER_FINISHED              = 2;

		public                          ListenerNode(int nodeType)
		{
			this.nodeType = nodeType;
		}

		public abstract void		executeSet();

		public abstract void		executeExcepted();

		public abstract void		executeCancelled();

		public final ListenerNode	getNextNode()
		{
			return nextNode;
		}

		public final int                getNodeType()
		{
			return nodeType;
		}

		private final int               nodeType;

		protected ListenerNode		nextNode;
	}

	protected static abstract class RegularListenerNode<V> extends ListenerNode<V>
	{
		public                          RegularListenerNode()
		{
			super(NT_REGULAR);
		}
	}

	protected static class MarkerListenerNode<V> extends ListenerNode<V>
	{
		public                          MarkerListenerNode(int nodeType)
		{
			super(nodeType);
		}

		@Override
		public void                     executeSet()
		{
			throw new UnsupportedOperationException("executeSet called on MarkerListenerNode.");
		}

		@Override
		public void                     executeExcepted()
		{
			throw new UnsupportedOperationException("executeExcepted called on MarkerListenerNode.");
		}

		@Override
		public void                     executeCancelled()
		{
			throw new UnsupportedOperationException("executeCancelled called on MarkerListenerNode.");
		}
	}

	private V                       result = null;

	private Throwable               excepted = null;

	private volatile int		status;

	private volatile ListenerNode   listeners = null;

	protected static final int      ST_DELAYED_CANCEL               = 1;
	protected static final int	ST_RUNNING                      = 2;
	protected static final int	ST_WAITING                      = 4;
	protected static final int	ST_FINISHED                     = 8;
	protected static final int	ST_CANCELLED                    = 16;

	protected static final ListenerNode MARKER_PROCESSING = new MarkerListenerNode(ListenerNode.NT_MARKER_PROCESSING);
	protected static final ListenerNode MARKER_FINISHED = new MarkerListenerNode(ListenerNode.NT_MARKER_FINISHED);

	protected static final AtomicIntegerFieldUpdater<AbstractFuture> statusUpdater = AtomicIntegerFieldUpdater.newUpdater(AbstractFuture.class, "status");
	protected static final AtomicReferenceFieldUpdater<AbstractFuture, ListenerNode> listenersUpdater = AtomicReferenceFieldUpdater.newUpdater(AbstractFuture.class, ListenerNode.class, "listeners");
}
