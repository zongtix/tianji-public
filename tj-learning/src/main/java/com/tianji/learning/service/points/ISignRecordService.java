package com.tianji.learning.service.points;


import com.tianji.learning.domain.vo.SignRecordVO;
import com.tianji.learning.domain.vo.SignResultVO;

import java.util.List;

public interface ISignRecordService {
    SignResultVO addSignRecords();


    SignRecordVO querySignRecords();

}
