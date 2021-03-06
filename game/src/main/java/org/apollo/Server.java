package org.apollo;

import com.google.common.base.Stopwatch;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.apollo.cache.IndexedFileSystem;
import org.apollo.game.model.World;
import org.apollo.game.plugin.PluginContext;
import org.apollo.game.plugin.PluginManager;
import org.apollo.game.release.r377.Release377;
import org.apollo.game.session.ApolloHandler;
import org.apollo.net.HttpChannelInitializer;
import org.apollo.net.JagGrabChannelInitializer;
import org.apollo.net.NetworkConstants;
import org.apollo.net.ServiceChannelInitializer;
import org.apollo.net.WebSocketJagGrabChannelInitializer;
import org.apollo.net.WebSocketServiceChannelInitializer;
import org.apollo.net.release.Release;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The core class of the Apollo server.
 *
 * @author Graham
 */
public final class Server {

	private static boolean USE_WS = true;

	/**
	 * The logger for this class.
	 */
	private static final Logger logger = Logger.getLogger(Server.class.getName());

	/**
	 * The entry point of the Apollo server application.
	 *
	 * @param args The command-line arguments passed to the application.
	 */
	public static void main(String[] args) {
		Stopwatch stopwatch = Stopwatch.createStarted();

		try {
			Server server = new Server();
			server.init(args.length == 1 ? args[0] : Release377.class.getName());

			SocketAddress service = new InetSocketAddress(NetworkConstants.SERVICE_PORT);
			SocketAddress serviceOrig = new InetSocketAddress(NetworkConstants.SERVICE_ORIG_PORT);
			SocketAddress http = new InetSocketAddress(NetworkConstants.HTTP_PORT);
			SocketAddress jaggrab = new InetSocketAddress(NetworkConstants.JAGGRAB_PORT);
			SocketAddress jaggrabOrig = new InetSocketAddress(NetworkConstants.JAGGRAB_ORIG_PORT);

			server.bind(service, serviceOrig, http, jaggrab, jaggrabOrig);
		} catch (Throwable t) {
			logger.log(Level.SEVERE, "Error whilst starting server.", t);
			System.exit(0);
		}

		logger.info("Starting apollo took " + stopwatch.elapsed(TimeUnit.MILLISECONDS) + " ms.");
	}

	/**
	 * The {@link ServerBootstrap} for the HTTP listener.
	 */
	private final ServerBootstrap httpBootstrap = new ServerBootstrap();

	/**
	 * The {@link ServerBootstrap} for the JAGGRAB listener.
	 */
	private final ServerBootstrap jaggrabBootstrap = new ServerBootstrap();

	/**
	 * The {@link ServerBootstrap} for the JAGGRAB listener.
	 */
	private final ServerBootstrap jaggrabOrigBootstrap = new ServerBootstrap();

	/**
	 * The event loop group.
	 */
	private final EventLoopGroup loopGroup = new NioEventLoopGroup();

	/**
	 * The {@link ServerBootstrap} for the service listener.
	 */
	private final ServerBootstrap serviceBootstrap = new ServerBootstrap();

	/**
	 * The {@link ServerBootstrap} for the service listener.
	 */
	private final ServerBootstrap origServiceBootstrap = new ServerBootstrap();

	/**
	 * Creates the Apollo server.
	 */
	public Server() {
		logger.info("Starting Apollo...");
	}

	/**
	 * Binds the server to the specified address.
	 *
	 * @param service The service address to bind to.
	 * @param http    The HTTP address to bind to.
	 * @param jaggrab The JAGGRAB address to bind to.
	 * @throws BindException If the ServerBootstrap fails to bind to the SocketAddress.
	 */
	public void bind(SocketAddress service, SocketAddress serviceOrig, SocketAddress http, SocketAddress jaggrab, SocketAddress jaggrabOrig) throws IOException {
		logger.fine("Binding service listener to address: " + service + "...");
		if (USE_WS) {
			bind(serviceBootstrap, service);
		} else {
			bind(origServiceBootstrap, serviceOrig);
		}

		try {
			logger.fine("Binding HTTP listener to address: " + http + "...");
			bind(httpBootstrap, http);
		} catch (IOException cause) {
			logger.log(Level.WARNING, "Unable to bind to HTTP - JAGGRAB will be used as a fallback.", cause);
		}
		logger.fine("Binding JAGGRAB listener to address: " + jaggrab + "...");

		if (USE_WS) {
			bind(jaggrabBootstrap, jaggrab);
		} else {
			bind(jaggrabOrigBootstrap, jaggrabOrig);
		}

		logger.info("Ready for connections.");
	}

	/**
	 * Initialises the server.
	 *
	 * @param releaseName The class name of the current active {@link Release}.
	 * @throws Exception If an error occurs.
	 */
	public void init(String releaseName) throws Exception {
		Class<?> clazz = Class.forName(releaseName);
		Release release = (Release) clazz.newInstance();
		int version = release.getReleaseNumber();

		logger.info("Initialized " + release + ".");

		serviceBootstrap.group(loopGroup);
		origServiceBootstrap.group(loopGroup);
		httpBootstrap.group(loopGroup);
		jaggrabBootstrap.group(loopGroup);
		jaggrabOrigBootstrap.group(loopGroup);

		World world = new World();
		ServiceManager services = new ServiceManager(world);
		IndexedFileSystem fs = new IndexedFileSystem(Paths.get("data/fs", Integer.toString(version)), true);
		ServerContext context = new ServerContext(release, services, fs);
		ApolloHandler handler = new ApolloHandler(context);
		if (USE_WS) {
			ChannelInitializer<SocketChannel> service = new WebSocketServiceChannelInitializer(handler);
			serviceBootstrap.channel(NioServerSocketChannel.class);
			serviceBootstrap.childHandler(service);
		} else {
			ChannelInitializer<SocketChannel> serviceOrig = new ServiceChannelInitializer(handler);
			origServiceBootstrap.channel(NioServerSocketChannel.class);
			origServiceBootstrap.childHandler(serviceOrig);
		}


		ChannelInitializer<SocketChannel> http = new HttpChannelInitializer(handler);
		httpBootstrap.channel(NioServerSocketChannel.class);
		httpBootstrap.childHandler(http);

		if (USE_WS) {
			ChannelInitializer<SocketChannel> jaggrab = new WebSocketJagGrabChannelInitializer(handler);
			jaggrabBootstrap.channel(NioServerSocketChannel.class);
			jaggrabBootstrap.childHandler(jaggrab);
		} else {
			ChannelInitializer<SocketChannel> jaggrabOrig = new JagGrabChannelInitializer(handler);
			jaggrabOrigBootstrap.channel(NioServerSocketChannel.class);
			jaggrabOrigBootstrap.childHandler(jaggrabOrig);
		}

		PluginManager manager = new PluginManager(world, new PluginContext(context));
		services.startAll();

		world.init(version, fs, manager);
	}

	/**
	 * Attempts to bind the specified ServerBootstrap to the specified SocketAddress.
	 *
	 * @param bootstrap The ServerBootstrap.
	 * @param address   The SocketAddress.
	 * @throws IOException If the ServerBootstrap fails to bind to the SocketAddress.
	 */
	private void bind(ServerBootstrap bootstrap, SocketAddress address) throws IOException {
		try {
			bootstrap.bind(address).sync();
		} catch (Exception cause) {
			throw new IOException("Failed to bind to " + address, cause);
		}
	}

}