package com.example.uhf.api.dto;
import java.util.List;

public class CashFlowDto {
    public String date;
    public int branch_id;
    public List<CashFlowItemDto> inflow;
    public List<CashFlowItemDto> outflow;
    public double total_inflow;
    public double total_outflow;
    public double net_cash_flow;
}

// CashFlowItemDto.java
package com.example.uhf.api.dto;

public class CashFlowItemDto {
    public String source_type;
    public String label;
    public String description;
    public String account;
    public double amount;
}
