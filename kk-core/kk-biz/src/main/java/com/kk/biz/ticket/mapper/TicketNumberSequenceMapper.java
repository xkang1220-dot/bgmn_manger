package com.kk.biz.ticket.mapper;

import com.kk.biz.ticket.entity.TicketNumberSequence;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

@Mapper
public interface TicketNumberSequenceMapper {

    @Select("SELECT company_id AS companyId, sequence_date AS sequenceDate, current_value AS currentValue "
            + "FROM ticket_number_sequence WHERE company_id = #{companyId} AND sequence_date = #{date} FOR UPDATE")
    TicketNumberSequence selectForUpdate(@Param("companyId") Long companyId, @Param("date") LocalDate date);

    @Insert("INSERT INTO ticket_number_sequence (company_id, sequence_date, current_value) "
            + "VALUES (#{companyId}, #{date}, 0)")
    int insertZero(@Param("companyId") Long companyId, @Param("date") LocalDate date);

    @Update("UPDATE ticket_number_sequence SET current_value = #{value} "
            + "WHERE company_id = #{companyId} AND sequence_date = #{date}")
    int updateValue(@Param("companyId") Long companyId, @Param("date") LocalDate date, @Param("value") long value);
}
