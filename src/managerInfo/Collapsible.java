package managerInfo;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

public final class Collapsible extends JPanel{
  private final String title;
  private final JButton head= new JButton();
  private final Component body;
  public Collapsible(String title, Component body, boolean open, Component... trailing){
    super(new BorderLayout());
    this.title= title;
    this.body= body;
    head.setHorizontalAlignment(SwingConstants.LEFT);
    head.setBorderPainted(false);
    head.setFocusPainted(false);
    head.setContentAreaFilled(false);
    head.setFont(head.getFont().deriveFont(Font.BOLD));
    head.addActionListener(_->setOpen(!body.isVisible()));
    setBorder(BorderFactory.createEtchedBorder());
    var bar= new JPanel(new BorderLayout());
    bar.add(head,BorderLayout.CENTER);
    if (trailing.length > 0){
      var right= new JPanel(new FlowLayout(FlowLayout.RIGHT,4,0));
      for(var c: trailing){ right.add(c); }
      bar.add(right,BorderLayout.EAST);
    }
    add(bar,BorderLayout.NORTH);
    add(body,BorderLayout.CENTER);
    setOpen(open);
  }
  public void setOpen(boolean open){
    body.setVisible(open);
    head.setText((open ? "[-] " : "[+] ")+title);
    revalidate();
    repaint();
  }
}
