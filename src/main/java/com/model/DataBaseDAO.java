package com.model;

import com.entity.Ban;
import com.exception.AdminCkeyIsNotFoundException;
import com.exception.CanNotGetConnectionException;
import com.exception.CkeyBanInfoIsNotFoundException;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.*;
import java.sql.Date;
import java.util.*;

public class DataBaseDAO {
    private static final Logger logger = Logger.getLogger(DataBaseDAO.class);
    private static DataBaseDAO dataBaseDAO;
    private static final Properties properties = new Properties();

    // Кэш и кэш-апдейтер. Кэш-апдейтер обновляет кэш каждый час.
    private CacheUpdater cacheUpdater = new CacheUpdater();
    private Set<String> availableCkeySet = new HashSet<>();
    private Set<String> availableAdminCkeySet = new HashSet<>();

    static {
        try {
            logger.info("Поиск JDBC драйвера для MySQL.");
            Class.forName("com.mysql.jdbc.Driver");
            logger.info("Загрузка JDBC драйвера для MySQL успешно завершена.");
        } catch (ClassNotFoundException e) {
            logger.error("JDBC драйвер для MySQL не был найден. Корректная работа программы невозможна без подключения к БД.", e);
        }

        String path = System.getProperty("os.name").toLowerCase().contains("linux") ?
                DataBaseDAO.class.getProtectionDomain().getCodeSource().getLocation().getPath().replace("%20", " ")
                :
                DataBaseDAO.class.getProtectionDomain().getCodeSource().getLocation().getPath().substring(1).replace("%20", " ");

        try (InputStream inputStream = Files.newInputStream(Paths.get(path + "database.properties"))) {
            properties.load(inputStream);
        } catch (IOException e) {
            logger.error("Файл database.properties не был найден. Пожалуйста, проверьте, что файл database.properties действительно существует в директории src, после чего перезапустите программу.", e);
            throw new CanNotGetConnectionException(e);
        }
    }

    /**
     * Возвращение DAO-объекта. lazy initialization. Во время инициализации заполняет кэш, что единожды может вызвать нагрузку.
     *
     * @return DAO
     */
    static DataBaseDAO getInstance() {
        if (dataBaseDAO == null) {
            logger.info("Инициализация DAO...");
            dataBaseDAO = new DataBaseDAO();

            try (Connection connection = dataBaseDAO.getConnection();
                 Statement statement = connection.createStatement()) {

                logger.info("Загрузка в кэш уникальных ckey-значений из БД...");

                ResultSet ckeyResults = statement.executeQuery("SELECT DISTINCT ckey FROM " + properties.get("ban_table"));

                while (ckeyResults.next())
                    dataBaseDAO.availableCkeySet.add(ckeyResults.getString("ckey"));

                ResultSet adminCkeyResults = statement.executeQuery("SELECT DISTINCT a_ckey FROM " + properties.get("ban_table"));

                while (adminCkeyResults.next())
                    dataBaseDAO.availableAdminCkeySet.add(adminCkeyResults.getString("a_ckey"));

                logger.info("Загрузка кэша успешно завершена. Всего загружено сикеев игроков: " + dataBaseDAO.availableCkeySet.size() + ", админов: " + adminCkeyResults);
            } catch (SQLException e) {
                logger.fatal("Ошибка загрузки кэша. Проверьте доступ к БД. Проверьте настройки в файле database.properties. Корректная работа программы невозможна без правильной инициализации кэша. Завершение программы...", e);
                System.exit(1);
            } catch (CanNotGetConnectionException e) {
                logger.fatal("Ошибка загрузки кэша. Не удалось соединиться с БД. Корректная работа программы невозможна без правильной инициализации кэша. Завершение программы...", e);
                System.exit(1);
            }
        }

        return dataBaseDAO;
    }

    /**
     * Метод для извлечения списка банов из БД. Конструрирует запрос на лету в зависимости от переданных параметров.
     * @param ckey Сикей игрока.
     * @param adminCkey Сикей администратора.
     * @param jobBan true - jobban, false - ban.
     * @param order Порядок, в котором будет проводиться сортировка.
     * @return Список извлеченных банов.
     * @throws CkeyBanInfoIsNotFoundException Игрока нет в кэше.
     */
    public List<Ban> getBans(String ckey, String adminCkey, boolean jobBan, Order order) throws CkeyBanInfoIsNotFoundException {
        if (isCkeyNotExisting(ckey))
            throw new CkeyBanInfoIsNotFoundException(ckey);

        String query = String.format("SELECT bantime, %sreason, duration, expiration_time, ckey, a_ckey, adminwho, bantype FROM %s WHERE ckey=?", jobBan ? "job, " : "", properties.get("ban_table"));
        String log = "Извлечение ";

        if (jobBan) {
            log += "джоббанов игрока " + ckey + ".";
            query += " AND (bantype='JOB_TEMPBAN' OR bantype='JOB_PERMABAN')";
        } else {
            log += "банов игрока " + ckey + ".";
            query += " AND (bantype='TEMPBAN' OR bantype='PERMABAN')";
        }

        if (adminCkey != null && !adminCkey.isEmpty()) {
            log += " Администратор, выдавший бан: " + adminCkey + ".";
            query += " AND a_ckey=?";
        }

        if (order != Order.NO_ORDER) {
            log += " Сортировка: " + order.getOrderQueryValue();
            query += " ORDER BY " + order.getOrderQueryValue();
        }

        logger.info(log);

        List<Ban> resultList = new ArrayList<>();

        try (Connection connection = getInstance().getConnection();
             PreparedStatement statement = connection.prepareStatement(query)) {

            statement.setString(1, ckey.toLowerCase());

            if (adminCkey != null && !adminCkey.isEmpty())
                statement.setString(2, adminCkey.toLowerCase());

            ResultSet resultSet = statement.executeQuery();
            retrieveBansInfo(resultList, resultSet);
        } catch (SQLException e) {
            logger.error("Ошибка извлечения списка банов игрока " + ckey + ". Возможные (но не все) причины: изменение структуры БД или отказ в доступе к БД.", e);
        }

        return resultList;
    }

    public List<Ban> getAdminBans(String adminCkey, boolean jobBan, Order order) throws AdminCkeyIsNotFoundException {
        if (isAdminCkeyNotExisting(adminCkey))
            throw new AdminCkeyIsNotFoundException();

        String query = String.format("SELECT bantime, %sreason, duration, expiration_time, ckey, a_ckey, adminwho, bantype FROM %s WHERE a_ckey=?", jobBan ? "job, " : "", properties.get("ban_table"));
        String log = "Извлечение всех ";

        if (jobBan) {
            log += "джоббанов администратора " + adminCkey + ".";
            query += " AND (bantype='JOB_TEMPBAN' OR bantype='JOB_PERMABAN')";
        } else {
            log += "банов администратора " + adminCkey + ".";
            query += " AND (bantype='TEMPBAN' OR bantype='PERMABAN')";
        }

        if (order != Order.NO_ORDER) {
            log += " Сортировка: " + order.getOrderQueryValue();
            query += " ORDER BY " + order.getOrderQueryValue();
        }

        logger.info(log);

        List<Ban> resultList = new ArrayList<>();

        try (Connection connection = getInstance().getConnection();
             PreparedStatement statement = connection.prepareStatement(query)) {

            statement.setString(1, adminCkey.toLowerCase());

            ResultSet resultSet = statement.executeQuery();
            retrieveBansInfo(resultList, resultSet);
        } catch (SQLException e) {
            logger.error("Ошибка извлечения списка банов администратора " + adminCkey + ". Возможные (но не все) причины: изменение структуры БД или отказ в доступе к БД.", e);
        }

        return resultList;
    }

    public void updateCash() {
        try (Connection connection = dataBaseDAO.getConnection();
             Statement statement = connection.createStatement()) {

            logger.info("Обновление кэша...");

            ResultSet ckeyResults = statement.executeQuery("SELECT DISTINCT ckey FROM " + properties.get("ban_table"));

            while (ckeyResults.next())
                dataBaseDAO.availableCkeySet.add(ckeyResults.getString("ckey"));

            ResultSet adminCkeyResults = statement.executeQuery("SELECT DISTINCT a_ckey FROM " + properties.get("ban_table"));

            while (adminCkeyResults.next())
                dataBaseDAO.availableAdminCkeySet.add(adminCkeyResults.getString("a_ckey"));

            logger.info("Обновление кэша завершено. Всего загружено сикеев игроков: " + dataBaseDAO.availableCkeySet.size() + ", админов: " + adminCkeyResults);
        } catch (SQLException | CanNotGetConnectionException e) {
            logger.error("Ошибка обновления кэша.", e);
        }
    }
    /**
     * Метод для установления соединения с базой данных.
     *
     * @return Инкапсуляция соединения с базой данных.
     */
    private Connection getConnection() throws CanNotGetConnectionException {
        try {
            return DriverManager.getConnection(properties.getProperty("url"), properties.getProperty("user"), properties.getProperty("password"));
        } catch (SQLException e) {
            logger.error("Ошибка подключения к базе данных.", e);
            throw new CanNotGetConnectionException(e);
        }
    }

    /**
     * Проверка, что есть хотя бы один бан у переданного сикея.
     * @param ckey Сикей игрока.
     * @return true - у игрока есть хотя бы один бан, false - игрок не существует или ранее не был забанен.
     */
    private boolean isCkeyNotExisting(String ckey) {
        return !availableCkeySet.contains(ckey.toLowerCase());
    }

    /**
     * Проверка, что сикей админа есть в кэше.
     * @param adminCkey Сикей админа.
     * @return true - сикей админа есть в кэше, false - его нет в кэше.
     */
    private boolean isAdminCkeyNotExisting(String adminCkey) {
        return !availableAdminCkeySet.contains(adminCkey.toLowerCase());
    }

    private void retrieveBansInfo(List<Ban> toRetrieve, ResultSet resultSet) throws SQLException {
        while (resultSet.next()) {
            switch (resultSet.getString("bantype")) {
                case "PERMABAN": {
                    try {
                        String reason = new String(resultSet.getBytes("reason"), "windows-1251");

                        toRetrieve.add(BanFactory.getPermaBan(resultSet.getString("ckey"), resultSet.getString("a_ckey"), reason,
                                resultSet.getString("adminwho"), new Date(resultSet.getTimestamp("bantime").getTime())));
                    } catch (UnsupportedEncodingException e) {
                        // It can't be.
                    }
                }
                break;
                case "TEMPBAN": {
                    try {
                        String reason = new String(resultSet.getBytes("reason"), "windows-1251");

                        toRetrieve.add(BanFactory.getTempBan(resultSet.getString("ckey"), resultSet.getString("a_ckey"), reason,
                                resultSet.getString("adminwho"), new Date(resultSet.getTimestamp("bantime").getTime()), resultSet.getInt("duration"), new Date(resultSet.getTimestamp("expiration_time").getTime())));
                    } catch (UnsupportedEncodingException e) {
                        // It can't be.
                    }
                }
                break;
                case "JOB_PERMABAN": {
                    try {
                        String reason = new String(resultSet.getBytes("reason"), "windows-1251");

                        toRetrieve.add(BanFactory.getPermaJobBan(resultSet.getString("ckey"), resultSet.getString("a_ckey"), resultSet.getString("job"),
                                reason, resultSet.getString("adminwho"), new Date(resultSet.getTimestamp("bantime").getTime())));
                    } catch (UnsupportedEncodingException e) {
                        // It can't be.
                    }
                }
                break;
                case "JOB_TEMPBAN": {
                    try {
                        String reason = new String(resultSet.getBytes("reason"), "windows-1251");

                        toRetrieve.add(BanFactory.getJobBan(resultSet.getString("ckey"), resultSet.getString("a_ckey"), resultSet.getString("job"),
                                reason, resultSet.getString("adminwho"), new Date(resultSet.getTimestamp("bantime").getTime()),
                                resultSet.getInt("duration"), new Date(resultSet.getTimestamp("expiration_time").getTime())));
                    } catch (UnsupportedEncodingException e) {
                        // It can't be.
                    }
                }
                break;
            }
        }
    }

    private static class CacheUpdater implements Runnable {
        private static final Logger logger = Logger.getLogger(CacheUpdater.class);

        private CacheUpdater() {
            Thread thread = new Thread(this);
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void run() {
            while (true) {
                try {
                    logger.info("Обновление кэша запланировано через один час!");
                    // Один час задержка.
                    Thread.sleep(60*60*1000);
                    getInstance().updateCash();
                    logger.info("Произведено обновление кэша!");
                } catch (InterruptedException e) {
                    logger.error("Остановка кэш-апдейтера...", e);
                    break;
                }
            }
        }
    }
    // singleton
    private DataBaseDAO() {

    }
}