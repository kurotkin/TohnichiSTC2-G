package com.kurotkin;

import com.esotericsoftware.yamlbeans.YamlException;
import com.esotericsoftware.yamlbeans.YamlReader;
import com.healthmarketscience.jackcess.*;
import com.itextpdf.text.DocumentException;
import jssc.SerialPort;
import jssc.SerialPortEvent;
import jssc.SerialPortEventListener;
import jssc.SerialPortException;
import jssc.SerialPortList;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.sql.SQLException;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class Main  {
    // Настройки по умолчанию
    private static String dbUrl = "С:/ProgramData/STAHLWILLE/Sensomaster4/Database.accdb";
    private static String tableName = "TohnichiSTC2-G";
    private static String prUrl = "C:/TohnichiSTC2-G";
    private static String portNameDefault = "COM3";
    // Параметры
    private static Logger log = Logger.getLogger(Main.class.getName());
    private static String oldLog = "";
    private static SerialPort serialPort;
    private static EventListener serialPortListener = new EventListener ();

    public static void main(String[] args) throws SQLException, ClassNotFoundException, IOException, InterruptedException, ParseException {
        loadLog();
        getSettingYaml();
        cleanDB();
        loadSerialPort();

        ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor();
        Runnable Runn = new Runnable() {
            @Override
            public void run() {
                String logMas = "[";
                try {
                    Database db = DatabaseBuilder.open(new File(dbUrl));
                    Table table = db.getTable(tableName);
                    Cursor cursor = CursorBuilder.createCursor(table);
                    Set<Integer> keys = Main.serialPortListener.points.keySet();
                    for(Integer i: keys) {
                        Double val = Main.serialPortListener.points.get(i);
                        for (Row row : cursor.newIterable().addMatchPattern("Код", i)) {
                            row.put("val", val);
                            table.updateRow(row);
                        }
                        System.out.println(Integer.toString(i) + " | " + Double.toString(val));
                        logMas = logMas + "{id:" + Integer.toString(i) + ",value:" + Double.toString(val) + "},";
                    }
                    db.flush();
                    db.close();
                } catch (IOException e) {
                    Main.log.warning("Ошибка " + e);
                    e.printStackTrace();
                }
                logMas = logMas + "]";
                if (!Main.oldLog.equals(logMas)) {
                    Main.log.info(logMas);
                    Main.oldLog = logMas;
                    try {
                        if(Main.serialPortListener.points.size() > 0) {
                            pdfWriter.writePdf(prUrl, Main.serialPortListener.points);
                        }
                    } catch (IOException | DocumentException e) {
                        e.printStackTrace();
                    }
                }
                System.out.println("__________");
            }
        };
        service.scheduleAtFixedRate(Runn, 0, 3, TimeUnit.SECONDS);
    }

    private static void loadLog() {
        try {
            LogManager.getLogManager().readConfiguration(Main.class.getResourceAsStream("logging.properties"));
        } catch (IOException e) {
            System.err.println("Could not setup logger configuration: " + e.toString());
        }
    }

    private static void loadSerialPort() {
        serialPort = new SerialPort(getPort());
        System.out.println("Start serial port " + getPort());
        try {
            serialPort.openPort ();
            serialPort.setParams (SerialPort.BAUDRATE_9600, SerialPort.DATABITS_8, SerialPort.STOPBITS_1, SerialPort.PARITY_NONE);
            serialPort.setEventsMask (SerialPort.MASK_RXCHAR);
            serialPort.addEventListener (serialPortListener);
        }
        catch (SerialPortException ex) {
            log.warning("Ошибка COM порта " + ex);
            ex.printStackTrace();
        }
    }

    private static void getSettingYaml() throws FileNotFoundException, YamlException {
        YamlReader reader = new YamlReader(new FileReader("settings.yml"));
        Object object = reader.read();
        Map map = (Map)object;
        dbUrl = map.get("dbUrl").toString();
        tableName = map.get("tableName").toString();
        portNameDefault = map.get("portName").toString();
        prUrl = map.get("prUrl").toString();
    }

    private static String getPort() {
        String[] portNames = SerialPortList.getPortNames();
        if(portNames.length == 1) {
            return portNames[0];
        } else {
            return portNameDefault;
        }
    }

    private static void cleanDB() throws ClassNotFoundException, SQLException, IOException, ParseException {
        Database db = DatabaseBuilder.open(new File(dbUrl));
        Table table = db.getTable(tableName);
        Cursor cursor = CursorBuilder.createCursor(table);
        int id = 1;
        while (id <= 30) {
            for (Row row : cursor.newIterable().addMatchPattern("Код", id)) {
                row.put("val", null);
                table.updateRow(row);
            }
            id++;
        }
        db.flush();
        db.close();
    }

    private static class EventListener implements SerialPortEventListener {
        HashMap<Integer, Double> points = new HashMap<>();
        public void serialEvent (SerialPortEvent event)  {
            if (event.isRXCHAR () && event.getEventValue () > 15){
                try {
                    String data = serialPort.readString (event.getEventValue ());
                    String num = data.subSequence(3, 7).toString();
                    String val = data.subSequence(9, 13).toString();
                    int n = Integer.parseInt(num);
                    double v = Double.parseDouble(val);
                    points.put(n,v);
                }
                catch (SerialPortException ex) {
                    log.warning("Ошибка " + ex);
                    System.out.println (ex);
                }
            }
        }
    }
}
