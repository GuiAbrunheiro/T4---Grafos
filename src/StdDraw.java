import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

public final class StdDraw {
    public static final Color BLACK = Color.BLACK;
    public static final Color BLUE = Color.BLUE;
    public static final Color RED = Color.RED;

    private static final int DEFAULT_SIZE = 512;

    private static JFrame frame;
    private static DrawPanel panel;
    private static BufferedImage offscreenImage;
    private static Graphics2D offscreen;
    private static int canvasWidth = DEFAULT_SIZE;
    private static int canvasHeight = DEFAULT_SIZE;
    private static double xmin = 0.0;
    private static double xmax = DEFAULT_SIZE;
    private static double ymin = 0.0;
    private static double ymax = DEFAULT_SIZE;
    private static double penRadius = 0.002;
    private static boolean doubleBuffering = false;
    private static final Queue<Character> keysTyped = new ArrayDeque<>();
    private static boolean mousePressed = false;
    private static double mouseX = 0.0;
    private static double mouseY = 0.0;
    private static final Object LOCK = new Object();
    private static final AtomicBoolean windowClosed = new AtomicBoolean(false);
    private static CountDownLatch closeLatch = new CountDownLatch(1);

    private StdDraw() {
    }

    public static void setCanvasSize(int width, int height) {
        synchronized (LOCK) {
            canvasWidth = Math.max(1, width);
            canvasHeight = Math.max(1, height);
        }
        ensureCanvas();
        runOnEdtAndWait(() -> {
            synchronized (LOCK) {
                rebuildOffscreen();
                panel.setPreferredSize(new Dimension(canvasWidth, canvasHeight));
                frame.pack();
                frame.setLocationRelativeTo(null);
                panel.revalidate();
                panel.repaint();
            }
        });
    }

    public static void setXscale(double min, double max) {
        synchronized (LOCK) {
            xmin = min;
            xmax = max;
        }
    }

    public static void setYscale(double min, double max) {
        synchronized (LOCK) {
            ymin = min;
            ymax = max;
        }
    }

    public static void setPenRadius(double radius) {
        synchronized (LOCK) {
            penRadius = radius;
        }
    }

    public static void setPenColor(Color color) {
        ensureCanvas();
        synchronized (LOCK) {
            offscreen.setColor(color);
        }
    }

    public static void enableDoubleBuffering() {
        synchronized (LOCK) {
            doubleBuffering = true;
        }
    }

    public static void clear() {
        ensureCanvas();
        synchronized (LOCK) {
            Color old = offscreen.getColor();
            offscreen.setColor(Color.WHITE);
            offscreen.fillRect(0, 0, canvasWidth, canvasHeight);
            offscreen.setColor(old);
        }
        drawIfNeeded();
    }

    public static void point(double x, double y) {
        ensureCanvas();
        synchronized (LOCK) {
            double xs = scaleX(x);
            double ys = scaleY(y);
            double radius = Math.max(2.0, penRadius * Math.max(canvasWidth, canvasHeight));
            offscreen.fill(new Ellipse2D.Double(xs - radius / 2.0, ys - radius / 2.0, radius, radius));
        }
        drawIfNeeded();
    }

    public static void line(double x0, double y0, double x1, double y1) {
        ensureCanvas();
        synchronized (LOCK) {
            float strokeWidth = (float) Math.max(1.0, penRadius * Math.max(canvasWidth, canvasHeight));
            offscreen.setStroke(new BasicStroke(strokeWidth));
            offscreen.draw(new Line2D.Double(scaleX(x0), scaleY(y0), scaleX(x1), scaleY(y1)));
        }
        drawIfNeeded();
    }

    public static void textLeft(double x, double y, String text) {
        ensureCanvas();
        synchronized (LOCK) {
            float xs = (float) scaleX(x);
            float ys = (float) scaleY(y);
            FontMetrics metrics = offscreen.getFontMetrics();
            offscreen.drawString(text, xs, ys + metrics.getAscent() / 2.0f);
        }
        drawIfNeeded();
    }

    public static void show() {
        ensureCanvas();
        runOnEdt(() -> {
            if (panel != null) {
                panel.repaint();
                Toolkit.getDefaultToolkit().sync();
            }
        });
    }

    public static void pause(int millis) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    public static boolean hasNextKeyTyped() {
        synchronized (LOCK) {
            return !keysTyped.isEmpty();
        }
    }

    public static char nextKeyTyped() {
        synchronized (LOCK) {
            return keysTyped.remove();
        }
    }

    public static boolean isMousePressed() {
        synchronized (LOCK) {
            return mousePressed;
        }
    }

    public static double mouseX() {
        synchronized (LOCK) {
            return mouseX;
        }
    }

    public static double mouseY() {
        synchronized (LOCK) {
            return mouseY;
        }
    }

    public static boolean isWindowOpen() {
        return !windowClosed.get();
    }

    public static void waitUntilClosed() {
        ensureCanvas();
        try {
            closeLatch.await();
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void drawIfNeeded() {
        boolean buffering;
        synchronized (LOCK) {
            buffering = doubleBuffering;
        }
        if (!buffering) {
            show();
        }
    }

    private static void ensureCanvas() {
        if (frame != null && panel != null && offscreen != null && frame.isDisplayable()) {
            return;
        }
        runOnEdtAndWait(() -> {
            if (frame != null && panel != null && offscreen != null && frame.isDisplayable()) {
                return;
            }

            windowClosed.set(false);
            closeLatch = new CountDownLatch(1);
            rebuildOffscreen();

            frame = new JFrame("T4 TSP Visualizer");
            panel = new DrawPanel();
            panel.setPreferredSize(new Dimension(canvasWidth, canvasHeight));
            panel.setFocusable(true);

            panel.addKeyListener(new KeyAdapter() {
                @Override
                public void keyTyped(KeyEvent event) {
                    synchronized (LOCK) {
                        keysTyped.add(event.getKeyChar());
                    }
                }
            });

            MouseAdapter mouseAdapter = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    synchronized (LOCK) {
                        mousePressed = true;
                    }
                    updateMouse(event);
                }

                @Override
                public void mouseReleased(MouseEvent event) {
                    synchronized (LOCK) {
                        mousePressed = false;
                    }
                    updateMouse(event);
                }

                @Override
                public void mouseDragged(MouseEvent event) {
                    updateMouse(event);
                }

                @Override
                public void mouseMoved(MouseEvent event) {
                    updateMouse(event);
                }
            };

            panel.addMouseListener(mouseAdapter);
            panel.addMouseMotionListener(mouseAdapter);

            frame.setContentPane(panel);
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent event) {
                    windowClosed.set(true);
                    closeLatch.countDown();
                    frame = null;
                    panel = null;
                    offscreenImage = null;
                    offscreen = null;
                }
            });

            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setAlwaysOnTop(true);
            frame.setVisible(true);
            frame.toFront();
            frame.requestFocus();
            frame.setAlwaysOnTop(false);
            panel.requestFocusInWindow();
        });
    }

    private static void rebuildOffscreen() {
        offscreenImage = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB);
        offscreen = offscreenImage.createGraphics();
        offscreen.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        offscreen.setColor(Color.WHITE);
        offscreen.fillRect(0, 0, canvasWidth, canvasHeight);
        offscreen.setColor(Color.BLACK);
    }

    private static void updateMouse(MouseEvent event) {
        synchronized (LOCK) {
            mouseX = unscaleX(event.getX());
            mouseY = unscaleY(event.getY());
        }
    }

    private static double scaleX(double x) {
        if (xmax == xmin) {
            return 0.0;
        }
        return canvasWidth * (x - xmin) / (xmax - xmin);
    }

    private static double scaleY(double y) {
        if (ymax == ymin) {
            return 0.0;
        }
        return canvasHeight - (canvasHeight * (y - ymin) / (ymax - ymin));
    }

    private static double unscaleX(double x) {
        return xmin + x * (xmax - xmin) / canvasWidth;
    }

    private static double unscaleY(double y) {
        return ymin + (canvasHeight - y) * (ymax - ymin) / canvasHeight;
    }

    private static void runOnEdt(Runnable runnable) {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
        }
        else {
            SwingUtilities.invokeLater(runnable);
        }
    }

    private static void runOnEdtAndWait(Runnable runnable) {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(runnable);
        }
        catch (Exception exception) {
            throw new IllegalStateException("nao foi possivel executar a interface grafica", exception);
        }
    }

    private static final class DrawPanel extends JPanel {
        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            BufferedImage snapshot;
            synchronized (LOCK) {
                snapshot = offscreenImage;
            }
            if (snapshot != null) {
                graphics.drawImage(snapshot, 0, 0, null);
            }
        }
    }
}
