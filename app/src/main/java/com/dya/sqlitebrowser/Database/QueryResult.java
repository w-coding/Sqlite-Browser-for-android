package com.dya.sqlitebrowser.Database;

import java.util.ArrayList;
import java.util.List;

public class QueryResult {
    public String[] columns = new String[0];
    public List<String[]> rows = new ArrayList<>();
    public List<Long> rowIds = new ArrayList<>();
    public boolean hasRowId = false;
    public String message = "";
    public int statements = 0;   // number of statements run by execute()
    public long millis = 0;      // time taken by execute()
}