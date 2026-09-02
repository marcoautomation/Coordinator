package manager;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Taskbar;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.JFileChooser;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import managerData.ManagerData;
import managerIcons.FolderName;
import managerInfo.FolderInfo;
import managerList.FolderList;
import userMessages.UserError;
import userMessages.Violation;

final class ManagerGui {
  private static final Duration tickDelay= Duration.ofSeconds(1);//the label shows seconds, so tick every second
  final JFrame frame;
  final JLabel elapsed;
  final FolderList folders;
  final JSplitPane split;
  private final JPanel body= new JPanel();
  private final JMenu running= new JMenu("Running");
  private final JMenu project= new JMenu("Project");
  private final ManagerData data;
  private final Executor worker;
  private final Consumer<ManagerGui> onForget;
  private final Runnable onQuit;
  private final Map<Path,FolderInfo> open= new LinkedHashMap<>();
  private FolderInfo shown;
  private ManagerGui(Runnable onQuit, ManagerData data, Executor worker, Consumer<ManagerGui> onForget){
    frame= new JFrame("Fearless Manager");
    this.data= data;
    this.worker= worker;
    this.onForget= onForget;
    this.onQuit= onQuit;
    elapsed= new JLabel("Running for 00:00:00");
    elapsed.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
    folders= new FolderList(data, this::isRunning, this::showFolder);
    folders.setBorder(BorderFactory.createTitledBorder("Registered project folders"));
    split= new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, folders, new JPanel());
    split.setResizeWeight(0.3);
    split.setDividerLocation(320);
    body.setLayout(new BorderLayout());
    body.add(folders, BorderLayout.CENTER);
    frame.setJMenuBar(menuBar());
    frame.setLayout(new BorderLayout());
    frame.add(body, BorderLayout.CENTER);
    frame.add(elapsed, BorderLayout.SOUTH);
    frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
    frame.addWindowListener(new WindowAdapter(){//closing the window only hides the manager; the Quit button terminates it
      @Override public void windowClosing(WindowEvent e){ frame.setVisible(false); }
    });
    setIcons();
    frame.setLocationByPlatform(true);
    frame.setSize(preferredSize());//the size a restore (un-maximize) falls back to
    frame.setExtendedState(Frame.MAXIMIZED_BOTH);
  }
  //760x900 is the restore size if un-maximized, capped against the usable
  //screen so it never lands partly off-screen on a shorter display.
  private static Dimension preferredSize(){
    var avail= GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
    return new Dimension(Math.min(760, avail.width), Math.min(900, avail.height));
  }
  //Same icon in the window, in the task bar and in the tray: one Fearless, whichever of
  //the three the desktop chooses to show. Whether a desktop shows a task bar icon at all
  //is its own business, so an unsupported task bar is not a failure (same policy as the tray).
  private void setIcons(){
    frame.setIconImage(FearlessIcon.image());
    if (!Taskbar.isTaskbarSupported()){ return; }
    var bar= Taskbar.getTaskbar();
    if (bar.isSupported(Taskbar.Feature.ICON_IMAGE)){ bar.setIconImage(FearlessIcon.image()); }
  }
  void nameFolder(ManagerData data, Path folder){
    var taken= data.registered().stream().map(e -> FolderName.compactName(e.folder())).collect(Collectors.toSet());
    FolderName.makeUnique(folder, taken, suggested -> askName(folder, suggested, taken));
  }
  private String askName(Path folder, String suggested, Set<String> taken){
    var result= new AtomicReference<String>();
    try { SwingUtilities.invokeAndWait(() -> result.set(prompt(folder, suggested, taken))); }
    catch(InterruptedException|InvocationTargetException e){ throw Violation.couldNotStartGui(e); }
    return result.get();
  }
  private String prompt(Path folder, String suggested, Set<String> taken){
    var question= """
      Another registered project is already called "%s".
      Choose the name to show for
      %s
      Names use lowercase letters, digits and single underscores.""".formatted(FolderName.compactName(folder), folder);
    while(true){
      var answer= JOptionPane.showInputDialog(frame, question, suggested);
      if (answer == null){ return suggested; }
      var name= answer.strip();
      if (FolderName.isName(folder,name) && !taken.contains(name)){ return name; }
    }
  }
  boolean askForget(){
    return ask("""
      Remove Fearless as the program registered to open Fearless projects?

      What your desktop already remembers by hand is left exactly as it is:
      this only removes what Fearless itself registered.""");
  }
  //Asked from the event thread by the button, and from the manager's own thread
  //by the offer: invokeAndWait is a deadlock when it is already the event thread.
  private boolean ask(String question){
    if (SwingUtilities.isEventDispatchThread()){ return confirm(question); }
    var result= new AtomicReference<Boolean>();
    try { SwingUtilities.invokeAndWait(() -> result.set(confirm(question))); }
    catch(InterruptedException|InvocationTargetException e){ throw Violation.couldNotStartGui(e); }
    return result.get();
  }
  private boolean confirm(String question){
    return JOptionPane.showConfirmDialog(frame, question, "Fearless", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
  }
  void explain(UserError problem){
    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame, problem.getMessage(), "Fearless", JOptionPane.WARNING_MESSAGE));
  }
  void select(Path folder){ SwingUtilities.invokeLater(()->folders.select(folder)); }
  private boolean isRunning(Path folder){
    var info= open.get(folder);
    return info != null && info.session().running().isPresent();
  }
  //A divider dragged to the icon-grid-only edge (see FolderInfo's zero minimum
  //size) would otherwise restore to that same edge position on every future
  //selection, silently reopening each panel too narrow to see.
  private static final int minPanelWidth= 300;
  private void showFolder(Optional<Path> folder){
    if (folder.isEmpty()){ hidePanel(); return; }
    shown= open.computeIfAbsent(folder.get(), f->new FolderInfo(data, f, worker, this::foldersChangedHere));
    var where= split.getDividerLocation();
    if (split.getWidth() > 0){ where= Math.min(where, split.getWidth()-minPanelWidth); }
    split.setLeftComponent(folders);
    split.setRightComponent(shown.panel());
    body.removeAll();
    body.add(split, BorderLayout.CENTER);
    split.setDividerLocation(where);
    fillProjectMenu();
    body.revalidate();
    body.repaint();
  }
  private void hidePanel(){
    shown= null;
    split.setRightComponent(new JPanel());
    body.removeAll();
    body.add(folders, BorderLayout.CENTER);
    fillProjectMenu();
    body.revalidate();
    body.repaint();
  }
  private JMenuBar menuBar(){
    var res= new JMenuBar();
    var manager= new JMenu("Manager");
    manager.setMnemonic('M');
    manager.add(item("Forget association",true,()->onForget.accept(this)));
    manager.add(item("Quit manager",true,onQuit));
    project.setMnemonic('P');
    running.setMnemonic('R');
    res.add(manager);
    res.add(project);
    res.add(running);
    fillProjectMenu();
    fillRunningMenu();
    return res;
  }
  private static JMenuItem item(String text, boolean enabled, Runnable action){
    var res= new JMenuItem(text);
    res.setEnabled(enabled);
    res.addActionListener(_->action.run());
    return res;
  }
  private void fillProjectMenu(){
    project.removeAll();
    project.add(item("Add folder...",true,this::addFolder));
    project.addSeparator();
    var on= shown;
    project.add(item("Clear cache",on != null,()->on.clearCache()));
    project.add(item("Browse files",on != null,()->on.browse()));
    project.add(item("Error report",on != null && !on.facts().valid(),()->on.report()));
    project.addSeparator();
    project.add(item("Forget project",on != null,()->on.forget()));
  }
  private void fillRunningMenu(){
    running.removeAll();
    var live= open.values().stream().filter(i->i.session().running().isPresent()).toList();
    if (live.isEmpty()){ running.add(item("<nothing running>",false,()->{})); return; }
    live.forEach(i->running.add(item(describe(i),true,()->folders.select(i.folder()))));
  }
  private static String describe(FolderInfo info){
    return FolderName.compactName(info.folder())+" - "+info.session().running().orElseThrow();
  }
  List<String> runningPrograms(){
    return open.values().stream().filter(i->i.session().running().isPresent()).map(ManagerGui::describe).toList();
  }
  private void addFolder(){
    var chooser= new JFileChooser();
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    chooser.setDialogTitle("Add a Fearless project folder");
    if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION){ return; }
    worker.execute(()->{ Manager.register(this, data, chooser.getSelectedFile().toString()); foldersChanged(); });
  }
  private void foldersChangedHere(){
    folders.refresh();
    var live= data.registered().stream().map(e->e.folder()).toList();
    open.values().stream().filter(i->!live.contains(i.folder())).forEach(i->i.session().terminate());
    open.keySet().removeIf(f->!live.contains(f));
    fillRunningMenu();
    if (shown != null && !live.contains(shown.folder())){ hidePanel(); }
  }
  void foldersChanged(){ SwingUtilities.invokeLater(folders::refresh); }
  static ManagerGui create(Runnable onQuit, ManagerData data, Executor worker, Consumer<ManagerGui> onForget){
    var result= new AtomicReference<ManagerGui>();
    try {
      SwingUtilities.invokeAndWait(() -> result.set(new ManagerGui(onQuit, data, worker, onForget)));
      return result.get();
    }
    catch(InterruptedException|InvocationTargetException e){ throw Violation.couldNotStartGui(e); }
  }
  void showManager(){
    SwingUtilities.invokeLater(() -> {
      frame.setVisible(true);
      frame.setExtendedState(frame.getExtendedState() & ~Frame.ICONIFIED);
      frame.toFront();
      frame.requestFocus();//best effort
    });
  }
  void tickLoop(){
    var start= Instant.now();
    while(tick(start)){}
  }
  private boolean tick(Instant start){
    var up= Duration.between(start, Instant.now());
    SwingUtilities.invokeLater(()->tickShown(up));
    try { Thread.sleep(tickDelay); return true; }
    catch(InterruptedException e){ return false; }//interruption is the designed stop signal for this worker
  }
  private void tickShown(Duration up){
    var status= shown == null ? "" : "   -   "+shown.status();
    elapsed.setText("Running for "+formatDuration(up)+status);
  }
  static String formatDuration(Duration duration){
    var seconds= duration.toSeconds();
    return "%02d:%02d:%02d".formatted(seconds/3600, (seconds/60)%60, seconds%60);
  }
}
